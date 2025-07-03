#!/usr/bin/env nbb
(ns send
  (:require
    [clojure.string :as string]
    ["ws$default" :as ws]
    [common :refer [get-args]]))

(defn main [& args]
  (let [command (string/join " " args)]
    (if (= "" command)
      (do
        (js/console.log "Usage: ./src/send.cljs <command>")
        (.exit js/process 1))
      (let [socket (ws. "ws://127.0.0.1:3001")]
        (.on socket "open"
             (fn []
               (.send socket command)))
        (.on socket "message"
             (fn [message]
               (js/console.log (js/JSON.stringify (js/JSON.parse (str message)) nil 2))
               (.close socket)
               (.exit js/process 0)))
        (.on socket "error"
             (fn [err]
               (js/console.error "Error connecting to minerepl. Is it running?")
               (js/console.error (.-message err))
               (.exit js/process 1)))))))

(defonce started
  (apply main (not-empty (get-args js/process.argv))))
