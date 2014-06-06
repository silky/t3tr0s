(ns server.core
  (:require-macros
    [cljs.core.async.macros :refer [go]])
  (:require
    [cljs.core.async :refer [<! timeout alts! chan]]
    [clojure.walk]
    [cljs.reader :refer [read-string]]
    [server.gif :refer [create-gif]]))

(enable-console-print!)

;;------------------------------------------------------------
;; Node libraries
;;------------------------------------------------------------

(def express (js/require "express"))
(def http    (js/require "http"))
(def socket  (js/require "socket.io"))

;;------------------------------------------------------------
;; Config
;;------------------------------------------------------------

(def config
  (-> (js/require "./config.json")
    js->clj
    clojure.walk/keywordize-keys))

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

(def game-count
  "Number of games started since server started (used to identify games)"
  (atom 0))

(def game-mode
  "The current game mode is nil, :lines, or :time"
  (atom nil))

(def quit-game-chan
  "Channel to close in order to halt the current game."
  (atom (chan)))

(defn rank-players
  "Get players for the given game sorted by rank for the given mode."
  [game-id mode]
  (->> (vals @players)
       (filter #(= game-id (:game %)))
       (sort-by :score)
       (reverse)))

(defn go-go-countdown!
  "Start the countdown"
  [io seconds]
  (go
    (doseq [i (reverse (range (inc seconds)))]
      (<! (timeout 1000))
      (js/console.log "countdown:" i)
      (.. io (to "game") (emit "countdown" i)))
  ))

(defn go-go-game!
  "Start a game."
  [io mode]

  ; Set the game mode.
  (swap! game-mode mode)

  ; Update the game count to uniquely identify current game.
  (swap! game-count inc)

  ; Log the game start.
  (js/console.log "Starting the game:" (name mode) @game-count)

  ; Make lobby players navigate to a countdown screen.
  (.. io (to "lobby") (emit "start-game"))

  (go

    ; Kick off and wait for the countdown for game start.
    (<! (go-go-countdown! io 5))

    ; Emit a message for every second left until game over.
    (if (= mode :time)
      (loop [s 60]

        (js/console.log "time left:" s)

        (.. io (to "game") (emit "time-left" s))
        (.. io (to "mc") (emit "time-left" s))

        (if-not (zero? s)

          ; Wait for either the timer or the quit channel.
          (let [t (timeout 1000)
                q @quit-game-chan
                [_ c] (alts! [t q])]
            (if (= c t)
              (recur (dec s)))))))

    ; Calculate final ranks.
    (let [ranks (rank-players @game-count @game-mode)]

      ; Show ranks on server console.
      (js/console.log "RANKS")
      (doseq [r ranks]
        (js/console.log (:user r) " - "
                        (:score r) " points - "
                        (:total-lines r) " lines"))

      ; Emit a game over message with ranks to players.
      (.. io (to "game") (emit "game-over"
                               (pr-str ranks)))

      ; Emit a game over message with final ranks to MC.
      (.. io (to "mc") (emit "game-over"
                             (pr-str ranks))))
    
    ; Clear the game mode when done.
    (reset! game-mode nil)

    (js/console.log "Game ended.")

    )
  )

;;------------------------------------------------------------------------------
;; Socket Events
;;------------------------------------------------------------------------------

(defn- on-chat-message [msg pid socket]
  (let [d (assoc (get @players pid) :type "msg" :msg msg)]
    (js/console.log "Player" pid "said:" msg)
    (.. socket -broadcast (to "lobby") (emit "new-message" (pr-str d)))))

(defn- on-update-player
  "Called when player sends an updated state."
  [data pid socket io]

  (js/console.log "Player" pid "updated.")

  ; Merge in the updated data into the player structure.
  ; Also update the game id, so we know which players are in the current game.
  (swap! players update-in [pid] merge data {:game @game-count})

  ; Send top ranked players to the MC.
  (let [ranks (take 5 (rank-players @game-count @game-mode))]
    (.. io (to "mc") (emit "update-players" (pr-str ranks))))
  )

;;------------------------------------------------------------------------------
;; Socket Setup
;;------------------------------------------------------------------------------

(defn init-socket
  "Initialize the web socket."
  [io socket]

  ; Create gif whenever "create-gif" is emitted.
  (.on socket "create-gif" #(create-gif (read-string %)))


  (let [pid (gen-player-id!)]

    (js/console.log "Player" pid "connected.")

    ; Attach player id to socket.
    (aset socket "pid" pid)

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
    (.on socket "chat-message" #(on-chat-message % pid socket))

    ; Join/leave the game.
    (.on socket "join-game" #(.join socket "game"))
    (.on socket "leave-game" #(.leave socket "game"))

    ; Receive the update from the player.
    (.on socket "update-player"
         #(on-update-player (read-string %) pid socket io))

    ; Request access to the MC role.
    (.on socket "request-mc"
         #(if (= % (:mc-password config))
            (do
              (js/console.log "Player" pid "granted as MC.")
              (.join socket "mc")
              (.emit socket "grant-mc" (pr-str @game-mode)))
            (do
              (js/console.log "Player" pid "rejected as MC."))))

    ; Leave the MC role.
    (.on socket "leave-mc"
         #(.leave socket "mc"))

    ; Start the game
    (.on socket "start-lines" #(go-go-game! io :line))
    (.on socket "start-time" #(go-go-game! io :time))

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
    (.listen server (:port config))
    (println "t3tr0s server listening on port" (:port config) "\n")

    ; configure sockets
    (.sockets.on io "connection" #(init-socket io %))))

(set! *main-cli-fn* -main)
