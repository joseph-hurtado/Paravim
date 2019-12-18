(ns paravim.buffers
  (:require [paravim.chars :as chars]
            [paravim.colors :as colors]
            [parinferish.core :as ps]
            [play-cljc.transforms :as t]
            [clojure.core.rrb-vector :as rrb]
            [clojure.string :as str]))

(defn clojurify-lines
  ([text-entity font-entity parsed-code parinfer?]
   (let [*line-num (volatile! 0)
         *char-num (volatile! -1)
         *char-counts (volatile! [])
         *collections (volatile! [])
         characters (reduce
                      (fn [characters data]
                        (clojurify-lines characters font-entity *line-num *char-num *char-counts *collections nil -1 data parinfer?))
                      (:characters text-entity)
                      parsed-code)
         ;; the last line's char count
         char-counts (vswap! *char-counts conj (inc @*char-num))
         ;; make sure the parinfer entity doesn't render any trailing characters it removed
         ;; see test: dedent-function-body
         characters (if (and (seq characters) parinfer?)
                      (reduce-kv
                        (fn [characters line-num char-count]
                          (update characters line-num
                                  (fn [char-entities]
                                    (if (> (count char-entities) char-count)
                                      (rrb/subvec char-entities 0 char-count)
                                      char-entities))))
                        characters
                        char-counts)
                      characters)
         ;; add characters to the attributes
         text-entity (if (seq characters)
                       (reduce-kv
                         (fn [entity line-num char-entities]
                           (if (not= (get-in entity [:characters line-num]) char-entities)
                             (chars/assoc-line entity line-num char-entities)
                             entity))
                         text-entity
                         characters)
                       text-entity)]
     (assoc text-entity :collections @*collections)))
  ([characters font-entity *line-num *char-num *char-counts *collections class-name depth data parinfer?]
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
                            (clojurify-lines characters font-entity *line-num *char-num *char-counts *collections class-name depth child parinfer?))
                          characters
                          children))]
       (when (= class-name :collection)
         (vswap! *collections conj {:start-line line-num
                                    :start-column (inc char-num)
                                    :end-line @*line-num
                                    :end-column (inc @*char-num)
                                    :depth depth}))
       characters)
     (let [color (colors/get-color class-name depth)]
       (reduce
         (fn [characters ch]
           (if (= ch \newline)
             (do
               (vswap! *char-counts conj (inc @*char-num))
               (vswap! *line-num inc)
               (vreset! *char-num -1)
               characters)
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
        (if (seq lines) ;; see test: delete-all-lines
          (rrb/subvec lines (+ first-line lines-to-remove))
          [])))))

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
            text-entity (if (seq (:characters text-entity)) ;; see test: delete-all-lines
                          (reduce
                            (fn [text-entity _]
                              (chars/dissoc-line text-entity first-line))
                            text-entity
                            (range 0 lines-to-remove))
                          text-entity)
            text-entity (reduce-kv
                          (fn [text-entity line-offset char-entities]
                            (chars/insert-line text-entity (+ first-line line-offset) char-entities))
                          text-entity
                          new-chars)]
        text-entity))))

(defn parse-clojure-buffer [{:keys [lines cursor-line cursor-column] :as buffer} {:keys [mode] :as state} init?]
  (let [parse-opts (cond
                     init? {:mode :paren} ;; see test: fix-bad-indentation
                     (= 'INSERT mode) {:mode :smart :cursor-line cursor-line :cursor-column cursor-column}
                     :else {:mode :indent})
        parsed-code (ps/parse (str/join "\n" lines) parse-opts)]
    (assoc buffer
      :parsed-code parsed-code
      :needs-parinfer? true)))

(defn update-clojure-buffer [{:keys [text-entity parsed-code lines] :as buffer} {:keys [base-font-entity font-height] :as state}]
  (let [text-entity (clojurify-lines text-entity base-font-entity parsed-code false)
        parinfer-text-entity (clojurify-lines text-entity base-font-entity parsed-code true)]
    (assoc buffer
      :text-entity (chars/update-uniforms text-entity font-height colors/text-alpha)
      :parinfer-text-entity (chars/update-uniforms parinfer-text-entity font-height colors/parinfer-alpha))))

(defn update-text-buffer [{:keys [lines] :as buffer} {:keys [base-font-entity font-height] :as state} new-lines first-line line-count-change]
  (-> buffer
      (assoc :lines (update-lines lines new-lines first-line line-count-change))
      (update :text-entity
              (fn [text-entity]
                (-> text-entity
                    (replace-lines base-font-entity new-lines first-line line-count-change)
                    (chars/update-uniforms font-height colors/text-alpha))))))
