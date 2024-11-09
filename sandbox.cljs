(ns sandbox
  (:require
    [common :refer [send-command on on-message]]))

; create an emerald block 2 blocks away from the player
; (send-command "setblock ~2 ~2 ~2 emerald_block")

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
  (fn [_message]
    ; build an emerald pole next to the player
    (doseq [z (range 10)]
      (send-command "setblock ~" 2 " ~" z "~" 0 " emerald_block"))))

; if the player places a block
(on "BlockPlaced"
    (fn [payload]
      (js/console.log (aget payload "body" "player" "position"))))
