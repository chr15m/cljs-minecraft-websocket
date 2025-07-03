(ns minerepl
  (:require
    ["os" :as os]
    [clojure.string :as string]
    [clojure.tools.cli :as cli]
    [applied-science.js-interop :as j]
    ["node-watch$default" :as watch]
    ["ws" :as ws]
    [nbb.core :refer [load-file *file*]]
    [common :refer [connections callbacks pending-requests get-args]]))

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

(defn socket-message
  [_socket packet]
  (let [payload (js/JSON.parse packet)
        header (j/get payload :header)
        message-purpose (j/get header :messagePurpose)
        request-id (j/get header :requestId)]

    (js/console.log "socket-message" payload)

    ; Check if it's a response to a command we sent
    (if (= message-purpose "commandResponse")
      (when-let [pending (get @pending-requests request-id)]
        ; Found a matching pending request
        ;(js/console.log "Resolving request:" request-id)
        (let [response-body (j/get payload :body)] ; <-- Extract body
          ; Log the body right before resolving
          ;(js/console.log "Value passed to resolve:" response-body) ; <-- Add log
          ; TODO: Clear any timeout associated with this request-id
          ((:resolve pending) response-body) ; <-- Resolve with extracted body
          (swap! pending-requests dissoc request-id))) ; Clean up
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
              (message-callback message-parts payload))))))))


(defn socket-error
  [_socket error]
  (js/console.error "Socket error" error))

(defn start-heartbeat [socket]
  (js/setTimeout
    (fn []
      (when (contains? @connections socket)
        ;(print "Socket heartbeat")
        (.send socket "ping")
        (start-heartbeat socket)))
    3000))

(defn socket-connection [socket]
  (js/console.log "connection" (boolean socket))
  (.on socket "close"
       (fn [] (js/console.log "socket close")
         (swap! connections disj socket)))
  (.on socket "message" #(socket-message socket %))
  (.on socket "error" #(socket-error socket %))
  (start-heartbeat socket)
  (subscribe-to-events socket "PlayerMessage")
  (subscribe-to-events socket "BlockPlaced"))

(defn get-local-ip-addresses []
  (let [interfaces (os/networkInterfaces)]
    (for [[_ infos] (js/Object.entries interfaces)
          info infos
          :when (= (.-family info) "IPv4")]
      (.-address info))))

(defn send-command-and-wait [cmd]
  (if-let [socket (first @connections)]
    (let [request-id (str (random-uuid))]
      (js/Promise.
       (fn [resolve reject]
         (swap! pending-requests assoc request-id {:resolve resolve :reject reject})
         (let [request-body
               (j/lit {:header {:version 1
                                :requestId request-id
                                :messageType "commandRequest"
                                :messagePurpose "commandRequest"}
                       :body {:commandLine cmd
                              :version 1}})]
           (.send socket (js/JSON.stringify request-body))))))
    (js/Promise.resolve #js {:statusCode -1
                             :statusMessage "No client connected."})))

(defonce websocket-server
  (let [server (new (.-WebSocketServer ws) #js {:port 3000})]
    (js/console.log "Listening on:")
    (doseq [ip (reverse (sort-by count (get-local-ip-addresses)))]
      (js/console.log (str "\t" ip ":3000")))
    (.on server "connection"
         (fn [socket]
           (swap! connections conj socket)
           (socket-connection socket)))))

(defonce cli-server
  (let [server (new (.-WebSocketServer ws) #js {:port 3001})]
    (js/console.log "CLI Command server listening on: ws://127.0.0.1:3001")
    (.on server "connection"
         (fn [socket]
           (.on socket "message"
                (fn [message]
                  (js/console.log "cli-server message:" (.toString message))
                  (let [cmd (str message)]
                    (-> (send-command-and-wait cmd)
                        (.then (fn [response]
                                 (.send socket (js/JSON.stringify response))
                                 (.close socket)))
                        (.catch (fn [err]
                                  (.send socket (js/JSON.stringify #js {:error (str err)}))
                                  (js/console.error "Error processing command:" err)
                                  (.close socket)))))))))))

(defonce watcher
  (watch #js [*file* "../sandbox.cljs"]
         (fn [_event-type filename]
           (js/console.log "Reloading" filename)
           (load-file filename))))

(defonce handle-error
  (.on js/process "uncaughtException"
       (fn [error]
         (js/console.error error))))

(def cli-options
  [["-r" "--repl" "Start an interactive REPL"]
   ["-h" "--help" "Show this help"]])

(defn print-usage [summary]
  (println "Usage: npx nbb src/minerepl.cljs [options] [command]")
  (println)
  (println "Options:")
  (println summary))

(defn main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
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
                      (js/process.exit 1))))))))

(defonce started
  (apply main (get-args js/process.argv)))
