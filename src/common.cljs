(ns common
  (:require [applied-science.js-interop :as j]))

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
(defn send-command [& args]
  (let [command (apply str args)
        request-id (str (random-uuid)) ; Generate unique ID
        payload (j/lit
                  {:header
                   {:version 1
                    :requestId request-id ; Include ID in header
                    :messageType "commandRequest"
                    :messagePurpose "commandRequest"}
                   :body
                   {:version 1
                    :commandLine command
                    :origin {:type "player"}}})
        json-payload (js/JSON.stringify payload)]

    ; Create and return a promise
    (js/Promise.
     (fn [resolve reject]
       ; Store the resolve/reject functions
       (swap! pending-requests assoc request-id {:resolve resolve :reject reject})

       ; TODO: Add a timeout mechanism here using js/setTimeout
       ; that calls reject and removes the request-id after a delay.

       ; Send the command to all connections
       ; Note: This assumes the first connection's response is sufficient.
       ; For multiple clients, this might need refinement.
       (doseq [socket @connections]
         (.send socket json-payload))))))

; Function to register an event callback
(defn on [event-name callback]
  (swap! callbacks assoc-in [:event event-name] callback))

; Function to register a player message callback
(defn on-message [first-word callback]
  (swap! callbacks assoc-in [:message first-word] callback))
