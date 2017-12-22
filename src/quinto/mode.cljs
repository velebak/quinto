(ns quinto.mode
  "Functions for transitioning the board's state in response to user input."
  (:require [com.rpl.specter :refer [select ALL LAST]]
            [quinto.ai :as ai]
            [quinto.deck :as deck]
            [quinto.grid :as g]
            [quinto.utils :refer [remove-item]]))

(defn enter-assembling-move-mode [state selected-cell]
  "Used when the user selects a green (playable) cell in order to begin
  assembling a move. Creates an :assembling-move mode dict which records
  the selected cell and is ready for further user input."
  (assert (contains? (set (g/find-playable-cells (state :grid)))
                     selected-cell))

  (assoc state
         :mode
         {:mode/type       :assembling-move
          :selected-cell   selected-cell
          :available-cells []
          :move-so-far     []
          :original-hand   (state :player-hand)
          :original-grid   (state :grid)}))

(defn select-cell [state cell]
  "Used when the board is already in assembling-move mode and the user
  has selected another cell they'd like to include in their move."
  (assert (nil? (get-in state [:mode :selected-cell])))

  (-> state
      (assoc-in [:mode :selected-cell] cell)
      (assoc-in [:mode :available-cells] [])))

(defn select-tile [state value]
  "Used when the board is in assembling-mode, the user has previously selected
  a tile to make a move on, and is now selecting a value to place on that tile."
  (assert (some? (get-in state [:mode :selected-cell])))

  (let [[x y] (get-in state [:mode :selected-cell])]
    (as-> state $
      (assoc-in $ [:grid x y] value)
      (assoc-in $ [:mode :selected-cell] nil)
      (update-in $ [:player-hand] remove-item value)
      (update-in $ [:mode :move-so-far] conj [[x y] value])
      (assoc-in $ [:mode :available-cells]
                (g/find-next-open-cells-for-move
                  ($ :grid)
                  (get-in $ [:mode :move-so-far]))))))

(defn cancel-mode [state]
  "Cancels out of assembling-mode and puts the board back into default mode."
  (cond-> state
    (contains? (state :mode) :original-grid) (assoc :grid (get-in state [:mode :original-grid]))
    (contains? (state :mode) :original-hand) (assoc :player-hand (get-in state [:mode :original-hand]))
    true (assoc :mode {:mode/type :default})))

(defn go-back [state]
  "Basically an undo button for assembling-move mode - backs out the most-recently-added
  part of the under-assembly move."
  (assert (not= (get-in state [:mode :mode/type])
                :default))

  (cond
    (and
      (some? (get-in state [:mode :selected-cell]))
      (= (count (get-in state [:mode :move-so-far]))
         0))
    (-> state
        (assoc :grid (get-in state [:mode :original-grid]))
        (assoc :mode {:mode/type :default}))

    (some? (get-in state [:mode :selected-cell]))
    (as-> state $
      (assoc-in $ [:mode :selected-cell] nil)
      (assoc-in $ [:mode :available-cells]
                (g/find-next-open-cells-for-move
                  ($ :grid)
                  (get-in $ [:mode :move-so-far]))))

    (seq (get-in state [:mode :move-so-far]))
    (let [[[x y] value] (last (get-in state [:mode :move-so-far]))]

      (-> state
          (assoc-in [:grid x y] nil)
          (update-in [:mode :move-so-far] pop)
          (update-in [:player-hand] conj value)
          (assoc-in [:mode :available-cells] [])
          (assoc-in [:mode :selected-cell] [x y])))))

(defn -make-ai-move [state]
  (let [move (ai/pick-move (state :grid) (state :ai-hand))
        move-tiles (select [ALL LAST] move)
        spent-hand (reduce remove-item (state :ai-hand) move-tiles)
        [new-deck new-hand] (deck/draw-tiles (state :deck)
                                             spent-hand
                                             (count move-tiles))]
    (-> state
        (update-in [:ai-scores] conj (g/score-move (state :grid) move))
        (assoc :grid (g/make-move (state :grid) move))
        (assoc :most-recent-computer-move move)
        (assoc :ai-hand new-hand)
        (assoc :deck new-deck))))

(defn confirm-move [state]
  "Used when the baord is in assembling-move mode and a valid move has been assembled.
  Applies the move to the board, then lets the AI make a move. Updates both players' scores."
  (let [move (get-in state [:mode :move-so-far])
        move-tiles (select [ALL LAST] move)
        [new-deck new-hand] (deck/draw-tiles (state :deck)
                                             (state :player-hand)
                                             (count move-tiles))
        new-state (-> state
                      (assoc :grid (g/make-move (get-in state [:mode :original-grid])
                                                move))
                      (assoc :mode {:mode/type :default})
                      (update-in [:player-scores]
                                 conj
                                 (g/score-move (get-in state [:mode :original-grid])
                                               move))
                      (assoc :deck new-deck)
                      (assoc :player-hand new-hand)
                      -make-ai-move)]

    (assert (g/is-grid-valid? (new-state :grid)))
    new-state))
