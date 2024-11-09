(ns minerepl
  (:require
    ["os" :as os]
    [applied-science.js-interop :as j]
    ["node-watch$default" :as watch]
    ["ws" :as ws]
    [nbb.core :refer [load-file *file*]]
    [common :refer [connections callbacks]]))

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
  [socket packet]
  (let [payload (js/JSON.parse packet)
        event-name (j/get-in payload [:header :eventName])
        callback (get @callbacks event-name)]
    (js/console.log payload)
    (when callback (callback socket payload))))

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
