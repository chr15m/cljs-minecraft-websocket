(ns sandbox
  {:clj-kondo/config '{:lint-as {promesa.core/let clojure.core/let
                                 promesa.core/loop clojure.core/loop
                                 promesa.core/recur clojure.core/recur}}}
  (:require
    [common :refer [send-command on on-message]]
    [promesa.core :as p]
    [applied-science.js-interop :as j]))

; create an emerald block 2 blocks away from the player
; (send-command "setblock ~2 ~2 ~2 emerald_block")

; Example using promesa to wait for the command response
#_ (p/let [response (send-command "setblock ~2 ~0 ~2 minecraft:cobblestone")]
     (js/console.log "Cobblestone command response:" response))

; Get the player's current position
#_ (p/let [result (send-command "querytarget @p")
           pos (j/get-in result [:details 0 :position])]
     ; pos = { x: 351.5, y: 74.62001037597656, z: -152.5 }
     (js/console.log "Player position:" pos))

; draw a wall of emeralds near the player
#_ (doseq [x (range 10)
        z (range 10)
        y [3]]
  (send-command "setblock ~" x " ~" z "~" y " emerald_block"))

; if the player types a chat message with the first word "test"
(on-message
  "test"
  (fn [message _payload]
    (js/console.log "got" (pr-str message))))

; if the player types a chat message "epole"
(on-message
  "epole"
  (fn [_message _payload]
    ; build an emerald pole next to the player
    (doseq [z (range 10)]
      (send-command "setblock ~" 2 " ~" z "~" 0 " emerald_block"))))

; if the player types a chat message "hole"
(on-message
  "hole"
  (fn [_message _payload]
    ; dig a big hole
    (doseq [x (range -2 3)
            y (range -2 3)
            z (range 0 -11 -1)]
      (send-command "setblock ~" x " ~" z " ~" y " minecraft:air destroy"))))

; if the player places a block
(on "BlockPlaced"
    (fn [payload]
      (js/console.log "BlockPlaced event, player pos:" (j/get-in payload [:body :player :position]))))
