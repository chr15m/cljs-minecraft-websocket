(ns common
  (:require
    [applied-science.js-interop :as j]
    [clojure.string :as string]))

; Store active websocket connections
(defonce connections
  (atom #{}))

; Store event/message callbacks
(defonce callbacks
  (atom {:event {}
         :message {}}))

; Store pending command requests {request-id {:resolve fn :reject fn}}
(defonce pending-requests (atom {}))

; Function to send a command string to all connected clients
; Returns a promise that resolves with the command response body
(defn send-command-and-wait [cmd]
  (if (seq (filter #(j/get % :minecraft-client) @connections))
    (let [request-id (str (random-uuid))]
      (js/Promise.
        (fn [resolve reject]
          (swap! pending-requests assoc request-id
                 {:resolve resolve
                  :reject reject
                  :query-target? (string/starts-with? cmd "querytarget")})
          (let [request-body
                (j/lit {:header {:version 1
                                 :requestId request-id
                                 :messageType "commandRequest"
                                 :messagePurpose "commandRequest"}
                        :body {:commandLine cmd
                               :version 1}})]
            (doseq [socket (filter #(j/get % :minecraft-client) @connections)]
              (.send socket (js/JSON.stringify request-body)))))))
    (js/Promise.resolve #js {:statusCode -1
                             :statusMessage "No client connected."})))

(defn send-command [& args]
  (let [command (string/join " " (map str args))]
    (send-command-and-wait command)))

; Function to register an event callback
(defn on [event-name callback]
  (swap! callbacks assoc-in [:event event-name] callback))

; Function to register a player message callback
(defn on-message [first-word callback]
  (swap! callbacks assoc-in [:message first-word] callback))
