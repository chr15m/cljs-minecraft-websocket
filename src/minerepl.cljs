(ns minerepl
  (:require
    ["os" :as os]
    [applied-science.js-interop :as j]
    ["node-watch$default" :as watch]
    ["ws" :as ws]
    [nbb.core :refer [load-file *file*]]
    ; Require pending-requests atom from common
    [common :refer [connections callbacks pending-requests]]))

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

(defonce websocket-server
  (let [server (ws/WebSocketServer. #js {:port 3000})]
    (js/console.log "Listening on:")
    (doseq [ip (reverse (sort-by count (get-local-ip-addresses)))]
      (js/console.log (str "\t" ip ":3000")))
    (.on server "connection"
         (fn [socket]
           (swap! connections conj socket)
           (socket-connection socket)))))

(defonce watcher
  (watch #js [*file* "../sandbox.cljs"]
         (fn [_event-type filename]
           (js/console.log "Reloading" filename)
           (load-file filename))))

(defonce handle-error
  (.on js/process "uncaughtException"
       (fn [error]
         (js/console.error error))))

(when (some #(= "--repl" %) (.-argv js/process))
  (let [readline (js/require "readline")
        rl (.createInterface readline #js {:input js/process.stdin
                                           :output js/process.stdout
                                           :prompt "minerepl> "})
        send-command-and-wait (fn [cmd]
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
                                                           :statusMessage "No client connected."})))]

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
    (.on rl "close" (fn [] (.exit js/process 0)))))
