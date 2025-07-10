#!/usr/bin/env -S npx nbb --classpath ./node_modules/minerepl/
(ns minerepl
  (:require
    ["os" :as os]
    ["fs" :refer [realpathSync existsSync]]
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    [applied-science.js-interop :as j]
    ["node-watch$default" :as watch]
    ["ws" :as ws]
    [nbb.core :refer [load-file *file*]]
    [common :refer [connections callbacks pending-requests send-command-and-wait]]))

(def self *file*)

(defn get-args [argv]
  (if *file*
    (let [argv-vec (mapv
                     #(try (realpathSync %)
                           (catch :default _e %))
                     (js->clj argv))
          script-idx (.indexOf argv-vec *file*)]
      ;(print "script-idx" script-idx)
      ;(print argv-vec)
      ;(print *file*)
      (when (>= script-idx 0)
        (not-empty (subvec argv-vec (inc script-idx)))))
    (not-empty (js->clj (.slice argv
                                (if
                                  (or
                                    (.endsWith
                                      (or (aget argv 1) "")
                                      "node_modules/nbb/cli.js")
                                    (.endsWith
                                      (or (aget argv 1) "")
                                      "/bin/nbb"))
                                  3 2))))))

(defn subscribe-to-events [socket event-name]
  (->>
    (j/lit
      {:header
       {:version 1
        :requestId (str (random-uuid))
        :messageType "commandRequest"
        :messagePurpose "subscribe"}
       :body {:eventName event-name}})
    js/JSON.stringify
    (.send socket)))

(defn start-heartbeat [socket]
  (js/setTimeout
    (fn []
      (when (contains? @connections socket)
        ;(print "Socket heartbeat")
        (.send socket "ping")
        (start-heartbeat socket)))
    3000))

(defn socket-message
  [socket packet]
  (let [payload (js/JSON.parse packet)]
    (if (j/get payload "minerepl-send")
      (do
        (j/assoc! socket :minecraft-client false)
        (let [cmd (j/get payload "command")]
          (js/console.log "cli-server message:" cmd)
          (-> (send-command-and-wait cmd)
              (.then (fn [response]
                       (.send socket (js/JSON.stringify response))
                       (.close socket)))
              (.catch (fn [err]
                        (.send socket (js/JSON.stringify #js {:error (str err)}))
                        (js/console.error "Error processing command:" err)
                        (.close socket))))))
      (let [header (j/get payload :header)
            message-purpose (j/get header :messagePurpose)
            request-id (j/get header :requestId)]

        (js/console.log "socket-message" payload)

        ; Check if it's a response to a command we sent
        (if (= message-purpose "commandResponse")
          (when-let [pending (get @pending-requests request-id)]
            ; Found a matching pending request
            ;(js/console.log "Resolving request:" request-id)
            (let [response-body (j/get payload :body)]
              (when (:query-target? pending)
                (let [details (j/get response-body :details)]
                  (when (and (string? details) (not (string/blank? details)))
                    (try
                      (j/assoc! response-body :details (js/JSON.parse details))
                      (catch :default _e
                        ; if parsing fails, we just leave details as a string
                        nil)))))
              ((:resolve pending) response-body)
              (swap! pending-requests dissoc request-id)))
          ; Otherwise, handle it as an event or player message
          (let [event-name (j/get header :eventName)
                message (j/get-in payload [:body :message])
                message-to (j/get-in payload [:body :receiver])
                event-callback (get-in @callbacks [:event event-name])]
            ; handle event callbacks
            (when event-callback (event-callback payload))
            ; handle player message callbacks
            (when (and (= event-name "PlayerMessage")
                       message
                       (= message-to "")) ; broadcasted chat message
              (let [message-parts (.split message " ")
                    first-word (first message-parts)
                    message-callback (get-in @callbacks [:message first-word])]
                (when message-callback
                  (message-callback message-parts payload))))))))))


(defn socket-error
  [_socket error]
  (js/console.error "Socket error" error))

(defn socket-connection [socket]
  (js/console.log "connection" (boolean socket))
  (j/assoc! socket :minecraft-client true)
  (start-heartbeat socket)
  (subscribe-to-events socket "PlayerMessage")
  (subscribe-to-events socket "BlockPlaced")
  (subscribe-to-events socket "BlockBroken")
  (subscribe-to-events socket "BookEdited")
  (subscribe-to-events socket "ItemCrafted")
  (subscribe-to-events socket "ItemSmelted")
  (subscribe-to-events socket "ItemUsed")
  (subscribe-to-events socket "ItemEquipped")
  (subscribe-to-events socket "ItemDropped")
  (subscribe-to-events socket "ItemAcquired")
  (subscribe-to-events socket "ItemNamed")
  (subscribe-to-events socket "ItemInteracted")
  (subscribe-to-events socket "MobKilled")
  (subscribe-to-events socket "MobInteracted")
  (subscribe-to-events socket "BlockBroken")
  (subscribe-to-events socket "EndOfDay")
  (subscribe-to-events socket "AwardAchievement")
  (.on socket "close"
       (fn [] (js/console.log "socket close")
         (swap! connections disj socket)))
  (.on socket "message" #(socket-message socket %))
  (.on socket "error" #(socket-error socket %)))

(defn get-local-ip-addresses []
  (let [interfaces (os/networkInterfaces)]
    (for [[_ infos] (js/Object.entries interfaces)
          info infos
          :when (= (.-family info) "IPv4")]
      (.-address info))))

(defn start-server-and-watch [watch-file]
  (let [server (new (.-WebSocketServer ws) #js {:port 3000})]
    (js/console.log "Listening on:")
    (doseq [ip (reverse (sort-by count (get-local-ip-addresses)))]
      (js/console.log (str "\t" ip ":3000")))
    (.on server "connection"
         (fn [socket]
           (swap! connections conj socket)
           (socket-connection socket))))
  (when (existsSync watch-file)
    (js/console.log "Loading" watch-file)
    (load-file watch-file)
    (js/console.log "Watching" watch-file)
    (watch (clj->js (filter identity [self watch-file]))
           (fn [_event-type filename]
             (js/console.log "Reloading" filename)
             (load-file filename)))))

(defonce handle-error
  (.on js/process "uncaughtException"
       (fn [error]
         (js/console.error error))))

(def cli-options
  [["-r" "--repl" "Start an interactive REPL"]
   ["-h" "--help" "Show this help"]])

(defn print-usage [summary]
  (println "Usage: npx nbb src/minerepl.cljs [options] [command]")
  (println "   or: npx nbb src/minerepl.cljs send <minecraft command>")
  (println)
  (println "Options:")
  (println summary))

(defn main [& args]
  ;(print "args" args)
  (if (= "send" (first args))
    (let [command (string/join " " (rest args))]
      (if (string/blank? command)
        (do
          (js/console.log "Usage: npx nbb src/minerepl.cljs send <command>")
          (.exit js/process 1))
        (let [socket (new (.-default ws) "ws://127.0.0.1:3000")]
          (.on socket "open"
               (fn []
                 (let [payload (j/lit {:minerepl-send true
                                       :command command})]
                   (.send socket (js/JSON.stringify payload)))))
          (.on socket "message"
               (fn [message]
                 (let [payload (js/JSON.parse (str message))]
                   (when (not= (j/get-in payload [:header :messagePurpose]) "subscribe")
                     (js/console.log (js/JSON.stringify payload nil 2))
                     (.close socket)
                     (.exit js/process 0)))))
          (.on socket "error"
               (fn [err]
                 (js/console.error "Error connecting to minerepl. Is it running?")
                 (js/console.error (.-message err))
                 (.exit js/process 1))))))
    (let [first-arg (first args)
          watch-file (if (and first-arg (string/ends-with? first-arg ".cljs"))
                       first-arg
                       "../sandbox.cljs")
          processed-args (if (= watch-file "../sandbox.cljs")
                           args
                           (rest args))
          {:keys [options arguments errors summary]} (cli/parse-opts processed-args cli-options)]
      (when (and (not errors) (not (:help options)))
        (start-server-and-watch watch-file))
      (cond
        errors
        (do
          (doseq [error errors]
            (println error))
          (print-usage summary)
          (js/process.exit 1))

        (:help options)
        (do
          (print-usage summary)
          (js/process.exit 0))

        (:repl options)
        (let [readline (js/require "readline")
              rl (.createInterface readline #js {:input js/process.stdin
                                                 :output js/process.stdout
                                                 :prompt "minerepl> "})]
          (js/console.log "Starting Minecraft REPL. Type a command and press enter. Press Ctrl+D to exit.")
          (.prompt rl)
          (.on rl "line"
               (fn [line]
                 (let [trimmed (.trim line)]
                   (if (not= "" trimmed)
                     (-> (send-command-and-wait trimmed)
                         (.then (fn [response]
                                  (js/console.log (js/JSON.stringify response nil 2))
                                  (.prompt rl)))
                         (.catch (fn [err]
                                   (js/console.error "Error:" err)
                                   (.prompt rl))))
                   (.prompt rl)))))
          (.on rl "close" (fn [] (.exit js/process 0))))

        (not-empty arguments)
        (let [cmd (string/join " " arguments)]
          (-> (send-command-and-wait cmd)
              (.then (fn [response]
                       (js/console.log (js/JSON.stringify response nil 2))
                       (js/process.exit 0)))
              (.catch (fn [err]
                        (js/console.error "Error:" err)
                        (js/process.exit 1)))))))))

(defonce started
  (apply main (get-args js/process.argv)))
