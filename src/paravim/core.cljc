(ns paravim.core
  (:require [paravim.utils :as utils]
            [paravim.chars :as chars]
            [parinferish.core :as ps]
            [clojure.string :as str]
            [play-cljc.gl.utils :as u]
            [play-cljc.gl.core :as c]
            [play-cljc.transforms :as t]
            [play-cljc.instances :as i]
            [play-cljc.gl.text :as text]
            [play-cljc.gl.entities-2d :as e]
            [play-cljc.primitives-2d :as primitives]
            [clojure.core.rrb-vector :as rrb]
            #?(:clj  [play-cljc.macros-java :refer [gl math]]
               :cljs [play-cljc.macros-js :refer-macros [gl math]])
            #?(:clj [paravim.text :refer [load-font-clj]]))
  #?(:cljs (:require-macros [paravim.text :refer [load-font-cljs]])))

(def orig-camera (e/->camera true))
(def tabs [:files :repl-in :repl-out])
(def tab->path {:files "scratch.clj"
                :repl-in "repl.in"
                :repl-out "repl.out"})

(defonce *state (atom {:mouse-x 0
                       :mouse-y 0
                       :current-buffer nil
                       :buffers {}
                       :buffer-updates []
                       :current-tab :files
                       :tab->buffer {}
                       :font-size-multiplier (float (/ 1 2))
                       :text-boxes {}
                       :bounding-boxes {}}))

(def bg-color [(/ 52 255) (/ 40 255) (/ 42 255) 0.95])

(def text-color [1 1 1 1])
(def cursor-color [(/ 112 255) (/ 128 255) (/ 144 255) 0.9])
(def select-color [(/ 148 255) (/ 69 255) (/ 5 255) 0.8])

(def text-alpha 1.0)
(def parinfer-alpha 0.15)
(def highlight-alpha 0.05)
(def unfocused-alpha 0.5)

(def yellow-color [(/ 255 255) (/ 193 255) (/ 94 255) 1])
(def tan-color [(/ 209 255) (/ 153 255) (/ 101 255) 1])
(def cyan-color [(/ 86 255) (/ 181 255) (/ 194 255) 1])
(def gray-color [(/ 150 255) (/ 129 255) (/ 133 255) 1])

(def colors {:number yellow-color
             :string tan-color
             :keyword cyan-color
             :comment gray-color})

(def orange-color [(/ 220 255) (/ 103 255) (/ 44 255) 1])
(def red-color [(/ 210 255) (/ 45 255) (/ 58 255) 1])
(def purple-color [(/ 163 255) (/ 67 255) (/ 107 255) 1])
(def green-color [(/ 65 255) (/ 174 255) (/ 122 255) 1])

(def rainbow-colors [orange-color
                     red-color
                     purple-color
                     green-color])

(defn get-color [class-name depth]
  (or (colors class-name)
      (case class-name
        :delimiter (nth rainbow-colors (mod depth (count rainbow-colors)))
        text-color)))

(defn set-alpha [color alpha]
  (assoc color 3 alpha))

(defn clojurify-lines
  ([text-entity font-entity parsed-code parinfer?]
   (let [*line-num (volatile! 0)
         *char-num (volatile! -1)
         *collections (volatile! [])]
     (as-> parsed-code $
           (reduce
             (fn [characters data]
               (clojurify-lines characters font-entity *line-num *char-num *collections nil -1 data parinfer?))
             (:characters text-entity)
             $)
           (if (seq $)
             (reduce-kv
               (fn [entity line-num char-entities]
                 (if (not= (get-in entity [:characters line-num]) char-entities)
                   (chars/assoc-line entity line-num char-entities)
                   entity))
               text-entity
               $)
             text-entity)
           (assoc $ :collections @*collections))))
  ([characters font-entity *line-num *char-num *collections class-name depth data parinfer?]
   (if (vector? data)
     (let [[class-name & children] data
           depth (cond-> depth
                         (= class-name :collection)
                         inc)
           line-num @*line-num
           char-num @*char-num
           characters (if (or (and parinfer? (-> data meta :action (= :remove)))
                              (and (not parinfer?) (-> data meta :action (= :insert))))
                        characters
                        (reduce
                          (fn [characters child]
                            (clojurify-lines characters font-entity *line-num *char-num *collections class-name depth child parinfer?))
                          characters
                          children))]
       (when (= class-name :collection)
         (vswap! *collections conj {:start-line line-num
                                    :start-column (inc char-num)
                                    :end-line @*line-num
                                    :end-column (inc @*char-num)
                                    :depth depth}))
       characters)
     (let [color (get-color class-name depth)]
       (reduce
         (fn [characters ch]
           (if (= ch \newline)
             (let [line-num @*line-num
                   char-num (inc @*char-num)]
               (vswap! *line-num inc)
               (vreset! *char-num -1)
               (if parinfer?
                 (update characters line-num
                   (fn [char-entities]
                     (if (not= (count char-entities) char-num)
                       (rrb/subvec char-entities 0 char-num)
                       char-entities)))
                 characters))
             (update characters @*line-num
               (fn [char-entities]
                 (update char-entities (vswap! *char-num inc)
                   (fn [entity]
                     (-> (if (and parinfer?
                                  (-> entity :character (not= ch)))
                           (chars/crop-char font-entity ch)
                           entity)
                         (t/color color))))))))
         characters
         data)))))

(defn update-lines [lines new-lines first-line line-count-change]
  (if (= 0 line-count-change)
    (reduce-kv
      (fn [lines line-offset line]
        (assoc lines (+ first-line line-offset) line))
      lines
      new-lines)
    (let [lines-to-remove (if (neg? line-count-change)
                            (+ (* -1 line-count-change) (count new-lines))
                            (- (count new-lines) line-count-change))]
      (rrb/catvec
        (rrb/subvec lines 0 first-line)
        new-lines
        (rrb/subvec lines (+ first-line lines-to-remove))))))

(defn replace-lines [text-entity font-entity new-lines first-line line-count-change]
  (let [new-chars (mapv
                    (fn [line]
                      (mapv
                        (fn [ch]
                          (chars/crop-char font-entity ch))
                        line))
                    new-lines)]
    (if (= 0 line-count-change)
      (reduce-kv
        (fn [text-entity line-offset char-entities]
          (chars/assoc-line text-entity (+ first-line line-offset) char-entities))
        text-entity
        new-chars)
      (let [lines-to-remove (if (neg? line-count-change)
                              (+ (* -1 line-count-change) (count new-lines))
                              (- (count new-lines) line-count-change))
            text-entity (reduce
                          (fn [text-entity _]
                            (chars/dissoc-line text-entity first-line))
                          text-entity
                          (range 0 lines-to-remove))
            text-entity (reduce-kv
                          (fn [text-entity line-offset char-entities]
                            (chars/insert-line text-entity (+ first-line line-offset) char-entities))
                          text-entity
                          new-chars)]
        text-entity))))

(defn ->cursor-entity [{:keys [font-width font-height base-rect-entity font-size-multiplier] :as state} line-chars line column]
  (let [left-char (get line-chars (dec column))
        curr-char (get line-chars column)
        {:keys [left width height]} curr-char
        width (or width font-width)
        left (or left
                 (some-> (:left left-char)
                         (+ (:width left-char)))
                 0)
        top (* line font-height)
        height (or height font-height)]
    (-> base-rect-entity
        (t/color cursor-color)
        (t/translate left top)
        (t/scale width height)
        (assoc :left (* left font-size-multiplier)
               :top (* top font-size-multiplier)
               :width (* width font-size-multiplier)
               :height (* height font-size-multiplier)))))

(defn update-cursor [{:keys [base-rects-entity font-size-multiplier text-boxes] :as state} game tab buffer-ptr]
  (if-let [text-box (get text-boxes tab)]
    (update-in state [:buffers buffer-ptr]
      (fn [{:keys [cursor-line cursor-column] :as buffer}]
        (let [line-chars (get-in buffer [:text-entity :characters cursor-line])
              {:keys [left top width height] :as cursor-entity} (->cursor-entity state line-chars cursor-line cursor-column)]
          (-> buffer
              (assoc :rects-entity (-> base-rects-entity
                                       (i/assoc 0 cursor-entity)
                                       (assoc :rect-count 1)))
              (as-> buffer
                    (let [{:keys [camera camera-x camera-y]} buffer
                          game-width (utils/get-width game)
                          game-height (utils/get-height game)
                          text-top ((:top text-box) game-height font-size-multiplier)
                          text-bottom ((:bottom text-box) game-height font-size-multiplier)
                          cursor-bottom (+ top height)
                          cursor-right (+ left width)
                          text-height (- text-bottom text-top)
                          camera-bottom (+ camera-y text-height)
                          camera-right (+ camera-x game-width)
                          camera-x (cond
                                     (< left camera-x)
                                     left
                                     (> cursor-right camera-right)
                                     (- cursor-right game-width)
                                     :else
                                     camera-x)
                          camera-y (cond
                                     (< top camera-y)
                                     top
                                     (> cursor-bottom camera-bottom)
                                     (- cursor-bottom text-height)
                                     :else
                                     camera-y)]
                      (assoc buffer
                        :camera (t/translate orig-camera camera-x (- camera-y text-top))
                        :camera-x camera-x
                        :camera-y camera-y)))))))
    state))

(defn update-mouse [{:keys [text-boxes current-tab bounding-boxes tab-text-entities font-size-multiplier] :as state} game x y]
  (let [game-width (utils/get-width game)
        game-height (utils/get-height game)
        {:keys [left right top bottom] :as text-box} (get text-boxes current-tab)
        hover (if (and text-box
                       (<= left x (- game-width right))
                       (<= (top game-height font-size-multiplier)
                           y
                           (bottom game-height font-size-multiplier)))
                :text
                (some
                  (fn [[k box]]
                    (let [{:keys [x1 y1 x2 y2]} box
                          x1 (* x1 font-size-multiplier)
                          y1 (* y1 font-size-multiplier)
                          x2 (* x2 font-size-multiplier)
                          y2 (* y2 font-size-multiplier)]
                      (when (and (<= x1 x x2) (<= y1 y y2))
                        k)))
                  bounding-boxes))]
    (assoc state
      :mouse-x x
      :mouse-y y
      :mouse-hover hover
      :mouse-type (cond
                    (= hover :text)
                    :ibeam
                    (contains? tab-text-entities hover)
                    :hand))))

(defn click-mouse [{:keys [mouse-hover tab-text-entities] :as state}]
  (if (contains? tab-text-entities mouse-hover)
    (assoc state :current-tab mouse-hover)
    state))

(defn change-tab [{:keys [current-tab] :as state} direction]
  (let [index (+ (.indexOf tabs current-tab)
                 direction)
        index (cond
                (neg? index) (dec (count tabs))
                (= index (count tabs)) 0
                :else index)]
    (assoc state :current-tab (nth tabs index))))

(defn update-uniforms [{:keys [characters] :as text-entity} font-height alpha]
  (update text-entity :uniforms assoc
      'u_char_counts (mapv count characters)
      'u_font_height font-height
      'u_alpha alpha))

(defn update-command [{:keys [base-text-entity base-font-entity base-rects-entity font-height command-start] :as state} text position]
  (let [command-text-entity (when text
                              (-> (chars/assoc-line base-text-entity 0 (mapv #(-> base-font-entity (chars/crop-char %) (t/color bg-color))
                                                                         (str command-start text)))
                                  (update-uniforms font-height text-alpha)))
        command-cursor-entity (when text
                                (let [line-chars (get-in command-text-entity [:characters 0])]
                                  (-> base-rects-entity
                                      (i/assoc 0 (->cursor-entity state line-chars 0 (inc position))))))]
    (assoc state
      :command-text text
      :command-text-entity command-text-entity
      :command-cursor-entity command-cursor-entity)))

(defn range->rects [text-entity font-height {:keys [start-line start-column end-line end-column] :as rect-range}]
  (let [{:keys [start-line start-column end-line end-column]}
        (if (or (> start-line end-line)
                (and (= start-line end-line)
                     (> start-column end-column)))
          {:start-line end-line
           :start-column end-column
           :end-line start-line
           :end-column start-column}
          rect-range)]
    (vec (for [line-num (range start-line (inc end-line))]
           (let [line-chars (-> text-entity :characters (nth line-num))
                 start-column (if (= line-num start-line) start-column 0)
                 end-column (if (= line-num end-line) end-column (count line-chars))
                 empty-columns (subvec line-chars 0 start-column)
                 filled-columns (subvec line-chars start-column end-column)]
             {:left (->> empty-columns (map :width) (reduce +))
              :top (* line-num font-height)
              :width (->> filled-columns (map :width) (reduce +))
              :height font-height})))))

(defn assoc-rects [{:keys [rect-count] :as rects-entity} rect-entity color rects]
  (reduce-kv
    (fn [rects-entity i {:keys [left top width height]}]
      (i/assoc rects-entity (+ i rect-count)
               (-> rect-entity
                   (t/color color)
                   (t/translate left top)
                   (t/scale width height))))
    (update rects-entity :rect-count + (count rects))
    rects))

(defn update-highlight [{:keys [font-height base-rect-entity] :as state} buffer-ptr]
  (update-in state [:buffers buffer-ptr]
    (fn [{:keys [text-entity cursor-line cursor-column] :as buffer}]
      (if-let [coll (->> (:collections text-entity)
                         (filter (fn [{:keys [start-line start-column end-line end-column]}]
                                   (and (or (< start-line cursor-line)
                                            (and (= start-line cursor-line)
                                                 (<= start-column cursor-column)))
                                        (or (> end-line cursor-line)
                                            (and (= end-line cursor-line)
                                                 (> end-column cursor-column))))))
                         first)]
        (let [color (set-alpha (get-color :delimiter (:depth coll)) highlight-alpha)
              rects (range->rects text-entity font-height coll)]
          (update buffer :rects-entity assoc-rects base-rect-entity color rects))
        buffer))))

(defn update-selection [{:keys [font-height base-rect-entity] :as state} buffer-ptr visual-range]
  (update-in state [:buffers buffer-ptr]
    (fn [{:keys [text-entity] :as buffer}]
      (let [rects (range->rects text-entity font-height visual-range)]
        (update buffer :rects-entity assoc-rects base-rect-entity select-color rects)))))

(defn get-extension
  [path]
  (some->> (str/last-index-of path ".")
           (+ 1)
           (subs path)
           str/lower-case))

(def clojure-exts #{"clj" "cljs" "cljc" "edn"})

(defn get-buffer [state buffer-ptr]
  (get-in state [:buffers buffer-ptr]))

(defn clojure-path? [path]
  (-> path get-extension clojure-exts))

(defn assoc-lines [text-entity font-entity font-height lines]
  (-> (reduce-kv
        (fn [entity line-num line]
          (chars/assoc-line entity line-num (mapv #(chars/crop-char font-entity %) line)))
        text-entity
        lines)
      (update-uniforms font-height text-alpha)))

(defn assoc-buffer [{:keys [base-font-entity base-text-entity font-height current-tab] :as state} buffer-ptr path lines]
  (assoc-in state [:buffers buffer-ptr]
    {:text-entity (assoc-lines base-text-entity base-font-entity font-height lines)
     :camera (t/translate orig-camera 0 0)
     :camera-x 0
     :camera-y 0
     :path path
     :lines lines
     :clojure? (or (= current-tab :repl-in)
                   (clojure-path? path))}))

(defn parse-clojure-buffer [{:keys [mode] :as state} buffer-ptr init?]
  (update-in state [:buffers buffer-ptr]
    (fn [{:keys [lines cursor-line cursor-column] :as buffer}]
      (let [parse-opts (cond
                         init? {:mode :paren}
                         (= 'INSERT mode) {:mode :smart :cursor-line cursor-line :cursor-column cursor-column}
                         :else {:mode :indent})
            parsed-code (ps/parse (str/join "\n" lines) parse-opts)]
        (assoc buffer
          :parsed-code parsed-code
          :needs-parinfer? true)))))

(defn update-clojure-buffer [{:keys [base-font-entity font-height] :as state} buffer-ptr]
  (update-in state [:buffers buffer-ptr]
    (fn [{:keys [text-entity parsed-code lines] :as buffer}]
      (let [text-entity (clojurify-lines text-entity base-font-entity parsed-code false)
            parinfer-text-entity (clojurify-lines text-entity base-font-entity parsed-code true)]
        (assoc buffer
          :text-entity (update-uniforms text-entity font-height text-alpha)
          :parinfer-text-entity (update-uniforms parinfer-text-entity font-height parinfer-alpha))))))

(defn update-text [{:keys [base-font-entity font-height] :as state} buffer-ptr new-lines first-line line-count-change]
  (update-in state [:buffers buffer-ptr]
    (fn [{:keys [lines] :as buffer}]
      (-> buffer
          (assoc :lines (update-lines lines new-lines first-line line-count-change))
          (update :text-entity
                  (fn [text-entity]
                    (-> text-entity
                        (replace-lines base-font-entity new-lines first-line line-count-change)
                        (update-uniforms font-height text-alpha))))))))

(defn assoc-attr-lengths [text-entity]
  (reduce
    (fn [text-entity attr-name]
      (let [type-name (u/get-attribute-type text-entity attr-name)
            {:keys [size iter]} (merge u/default-opts (u/type->attribute-opts type-name))]
        (assoc-in text-entity [:attribute-lengths attr-name]
                  (* size iter))))
    text-entity
    (keys chars/instanced-font-attrs->unis)))

(defn init [game]
  ;; allow transparency in images
  (gl game enable (gl game BLEND))
  (gl game blendFunc (gl game SRC_ALPHA) (gl game ONE_MINUS_SRC_ALPHA))
  ;; create rect entities
  (let [rect-entity (e/->entity game primitives/rect)
        rects-entity (c/compile game (i/->instanced-entity rect-entity))]
    (swap! *state assoc
      :base-rect-entity rect-entity
      :base-rects-entity rects-entity))
  ;; load fonts
  (#?(:clj load-font-clj :cljs load-font-cljs) :firacode
     (fn [{:keys [data]} baked-font]
       (let [font-entity (-> (text/->font-entity game data baked-font)
                             (t/color text-color))
             text-entity (-> (i/->instanced-entity font-entity)
                             (assoc :vertex chars/instanced-font-vertex-shader
                                    :fragment chars/instanced-font-fragment-shader
                                    :characters [])
                             assoc-attr-lengths)
             text-entity (c/compile game text-entity)
             font-width (-> baked-font :baked-chars (nth (- 115 (:first-char baked-font))) :w)
             font-height (:font-height baked-font)
             snap-to-top (fn [game-height multiplier] (* font-height multiplier))
             snap-to-bottom (fn [game-height multiplier] (- game-height (* font-height multiplier)))
             repl-edge (fn [game-height multiplier] (- game-height (* 5 font-height multiplier)))]
         (swap! *state assoc
           :font-width font-width
           :font-height font-height
           :base-font-entity font-entity
           :base-text-entity text-entity
           :text-boxes {:files {:left 0 :right 0 :top snap-to-top :bottom snap-to-bottom}
                        :repl-in {:left 0 :right 0 :top repl-edge :bottom snap-to-bottom}
                        :repl-out {:left 0 :right 0 :top snap-to-top :bottom repl-edge}})
         (#?(:clj load-font-clj :cljs load-font-cljs) :roboto
          (fn [{:keys [data]} baked-font]
            (let [font-entity (-> (text/->font-entity game data baked-font)
                                  (t/color text-color))
                  text-entity (-> (i/->instanced-entity font-entity)
                                  (assoc :vertex chars/instanced-font-vertex-shader
                                         :fragment chars/instanced-font-fragment-shader
                                         :characters [])
                                  assoc-attr-lengths)
                  text-entity (c/compile game text-entity)
                  files-text-entity (assoc-lines text-entity font-entity font-height ["Files"])
                  repl-in-text-entity (assoc-lines text-entity font-entity font-height ["REPL In"])
                  repl-out-text-entity (assoc-lines text-entity font-entity font-height ["REPL Out"])
                  tab-spacing (* font-width 2)
                  tab-entities {:files files-text-entity
                                :repl-in repl-in-text-entity
                                :repl-out repl-out-text-entity}]
              (swap! *state assoc
                :roboto-font-entity font-entity
                :roboto-text-entity text-entity
                :tab-text-entities tab-entities
                :bounding-boxes (reduce-kv
                                  (fn [m i tab]
                                    (let [last-tab (some->> (get tabs (dec i)) (get m))
                                          left (if last-tab (+ (:x2 last-tab) tab-spacing) 0)
                                          right (-> tab-entities (get tab) :characters first last :x-total (+ left))]
                                      (assoc m tab {:x1 left :y1 0 :x2 right :y2 font-height})))
                                  {}
                                  tabs)))))))))

(def screen-entity
  {:viewport {:x 0 :y 0 :width 0 :height 0}
   :clear {:color bg-color :depth 1}})

(defn render-buffer [game {:keys [buffers text-boxes font-size-multiplier] :as state} game-width game-height current-tab buffer-ptr show-cursor?]
  (when-let [{:keys [rects-entity text-entity parinfer-text-entity camera]} (get buffers buffer-ptr)]
    (when-let [{:keys [top bottom]} (get text-boxes current-tab)]
      (when (and rects-entity show-cursor?)
        (c/render game (-> rects-entity
                           (t/project game-width game-height)
                           (t/camera camera)
                           (t/scale font-size-multiplier font-size-multiplier))))
      (let [min-y (- game-height (bottom game-height font-size-multiplier))
            max-y (- game-height (top game-height font-size-multiplier))]
        (when parinfer-text-entity
          (c/render game (-> parinfer-text-entity
                             (update :uniforms assoc 'u_min_y min-y 'u_max_y max-y)
                             (t/project game-width game-height)
                             (t/camera camera)
                             (t/scale font-size-multiplier font-size-multiplier))))
        (c/render game (-> text-entity
                           (update :uniforms assoc 'u_min_y min-y 'u_max_y max-y)
                           (cond-> (not show-cursor?)
                                   (assoc-in [:uniforms 'u_alpha] unfocused-alpha))
                           (t/project game-width game-height)
                           (t/camera camera)
                           (t/scale font-size-multiplier font-size-multiplier)))))))

(defn tick [game]
  (let [game-width (utils/get-width game)
        game-height (utils/get-height game)
        {:keys [current-buffer
                base-rect-entity base-rects-entity
                command-text-entity command-cursor-entity
                font-height mode font-size-multiplier
                tab-text-entities bounding-boxes current-tab tab->buffer]
         :as state} @*state]
    (c/render game (update screen-entity :viewport
                           assoc :width game-width :height game-height))
    (render-buffer game state game-width game-height current-tab current-buffer true)
    (case current-tab
      :repl-in (when-let [buffer-ptr (tab->buffer :repl-out)]
                 (render-buffer game state game-width game-height :repl-out buffer-ptr false))
      :repl-out (when-let [buffer-ptr (tab->buffer :repl-in)]
                  (render-buffer game state game-width game-height :repl-in buffer-ptr false))
      nil)
    (when (and base-rects-entity base-rect-entity)
      (c/render game (-> base-rects-entity
                         (t/project game-width game-height)
                         (i/assoc 0 (-> base-rect-entity
                                        (t/color bg-color)
                                        (t/translate 0 0)
                                        (t/scale game-width (* font-size-multiplier font-height))))
                         (i/assoc 1 (-> base-rect-entity
                                        (t/color (if (= 'COMMAND_LINE mode) tan-color bg-color))
                                        (t/translate 0 (- game-height (* font-size-multiplier font-height)))
                                        (t/scale game-width (* font-size-multiplier font-height)))))))
    (doseq [[k entity] tab-text-entities]
      (c/render game (-> entity
                         (assoc-in [:uniforms 'u_alpha] (if (= k current-tab) text-alpha unfocused-alpha))
                         (t/project game-width game-height)
                         (t/translate (-> bounding-boxes k :x1 (* font-size-multiplier)) 0)
                         (t/scale font-size-multiplier font-size-multiplier))))
    (when (and (= mode 'COMMAND_LINE)
               command-cursor-entity
               command-text-entity)
      (c/render game (-> command-cursor-entity
                         (t/project game-width game-height)
                         (t/translate 0 (- game-height (* font-size-multiplier font-height)))
                         (t/scale font-size-multiplier font-size-multiplier)))
      (c/render game (-> command-text-entity
                         (update :uniforms assoc 'u_min_y 0 'u_max_y font-height)
                         (t/project game-width game-height)
                         (t/translate 0 (- game-height (* font-size-multiplier font-height)))
                         (t/scale font-size-multiplier font-size-multiplier)))))
  ;; return the game map
  game)

