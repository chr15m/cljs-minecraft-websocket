(ns sandbox
  (:require
    [common :refer [send-command on]]))

; draw a wall of emeralds
#_ (doseq [x (range 10)
        z (range 10)
        y [3]]
  (send-command "setblock ~" x " ~" z "~" y " emerald_block"))

; (send-command "setblock ~2 ~2 ~2 emerald_block")

(on "BlockPlaced"
    (fn [_socket payload]
      (js/console.log (aget payload "body" "player" "position"))))
