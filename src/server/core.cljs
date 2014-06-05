(ns server.core
  (:require
    [cljs.reader :refer [read-string]]
    [server.gif :refer [create-gif]]))

(enable-console-print!)

(def port 1984)

;;------------------------------------------------------------
;; Node libraries
;;------------------------------------------------------------

(def express (js/require "express"))
(def http    (js/require "http"))
(def socket  (js/require "socket.io"))

;;------------------------------------------------------------
;; Player IDs
;;------------------------------------------------------------

(def players
  "Table of connected players."
  (atom {}))

(def player-count
  "Total number of players who have connected since server started."
  (atom 0))

(defn gen-player-id!
  "Create a new unique player ID."
  []
  (swap! player-count inc)
  @player-count)

(def anon-player {:user "Anon" :color 0})

;;------------------------------------------------------------
;; Game Runner
;;------------------------------------------------------------

(def game-status
  "Current status of the game (nil :lines or :time)."
  (atom nil))

(def quit-game-chan
  "Channel to close in order to halt the current game."
  (atom nil))

(defn go-go-game-lines!
  "Start a game.  Winner is first to fill 40 lines."
  []
  (js/console.log "TODO: start game lines")
  ; 1. Emit countdown chat messages
  ; 2. Emit game start
  nil)

(defn go-go-game-time!
  "Start a game.  Winner is highest score after 5 minutes."
  []
  (js/console.log "TODO: start game time")
  ; 1. Emit countdown chat messages
  ; 2. Emit game start
  nil)

;;------------------------------------------------------------
;; Socket Setup
;;------------------------------------------------------------

(defn init-socket
  "Initialize the web socket."
  [io socket]

  ; Create gif whenever "create-gif" is emitted.
  (.on socket "create-gif" #(create-gif (read-string %)))


  (let [pid (gen-player-id!)]

    (js/console.log "Player" pid "connected.")

    ; Add to player table as "Anon" for now.
    (swap! players assoc pid anon-player)

    ; Request that the client emit an "update-name" message back
    ; in case the server restarts and we need user info again.
    (.emit socket "request-name")

    ; Remove player from table when disconnected.
    (.on socket "disconnect"
         #(do
            (js/console.log "Player" pid "disconnected.")
            (swap! players dissoc pid)))

    ; Update player name when requested.
    (.on socket "update-name"
         #(let [data (read-string %)]
            (js/console.log "Updating player" pid "with" (:user data) (:color data))
            (swap! players update-in [pid] merge data)))

    ; Join the lobby.
    (.on socket "join-lobby"
         #(let [data (assoc (get @players pid) :type "join")]
            (js/console.log "Player" pid "joined the lobby.")
            (.. socket -broadcast (to "lobby") (emit "new-message" (pr-str data)))
            (.join socket "lobby")))

    ; Leave the lobby.
    (.on socket "leave-lobby"
         #(let [data (assoc (get @players pid) :type "leave")]
            (js/console.log "Player" pid "left the lobby.")
            (.. socket -broadcast (to "lobby") (emit "new-message" (pr-str data)))
            (.leave socket "lobby")))

    ; Chat in the lobby.
    (.on socket "chat-message"
         #(let [msg %
                data (assoc (get @players pid) :type "msg" :msg msg)]
           (js/console.log "Player" pid "said:" msg)
           (.. socket -broadcast (to "lobby") (emit "new-message" (pr-str data)))
           ))

    ; Join/leave the game.
    (.on socket "join-game" #(.join socket "game"))
    (.on socket "leave-game" #(.leave socket "game"))

    ; Request access to the MC role.
    (.on socket "request-mc"
         #(if (= % "thepleasure")
            (do
              (js/console.log "Player" pid "granted as MC.")
              (.join socket "mc")
              (.emit socket "grant-mc" (pr-str @game-status)))
            (do
              (js/console.log "Player" pid "rejected as MC."))))

    ; Leave the MC role.
    (.on socket "leave-mc"
         #(.leave socket "mc"))

    ; Start the game
    (.on socket "start-lines" go-go-game-lines!)
    (.on socket "start-time" go-go-game-time!)

    ))

;;------------------------------------------------------------
;; Main
;;------------------------------------------------------------

(defn -main [& args]

  (let [app    (express)
        server (.createServer http app)
        io     (.listen socket server)]

    ; configure express app
    (doto app
      (.get "/" (fn [req res] (.send res (html/page-shell))))
      (.use (.static express (str js/__dirname "/public"))))

    ; start server
    (.listen server port)
    (println "listening on port" port "\n")

    ; configure sockets
    (.sockets.on io "connection" #(init-socket io %))))

(set! *main-cli-fn* -main)
