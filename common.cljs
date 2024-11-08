(ns common)

(defonce connections
  (atom #{}))

(defn socket-send [socket packet]
  (->> packet
       clj->js
       js/JSON.stringify
       (.send socket)))

(defn command-packet [command]
  {:header {:version 1
            :requestId (str (random-uuid))
            :messagePurpose "commandRequest"
            :messageType "commandRequest"}
   :body {:version 1
          :commandLine command
          :origin {:type "player"}}})

(defn send-command [& command]
  (doseq [socket @connections]
    (socket-send socket (command-packet (apply str command)))))
