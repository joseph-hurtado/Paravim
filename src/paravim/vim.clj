(ns paravim.vim
  (:require [paravim.core :as c]
            [paravim.session :as session]
            [paravim.constants :as constants]
            [paravim.utils :as utils]
            [libvim-clj.core :as v]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.core.async :as async]
            [parinferish.core :as par])
  (:import [java.time LocalDate]))

(def ^:dynamic *update-ui?* true)
(def ^:dynamic *update-window?* true)

(defn set-window-size! [vim session width height]
  (let [{:keys [font-height font-width] :as constants} (session/get-constants session)
        current-tab (:id (session/get-current-tab session))]
    (when-let [{:keys [top bottom]} (session/get-text-box session {:?id current-tab})]
      (let [font-size-multiplier (:size (session/get-font session))
            text-height (- (bottom height font-size-multiplier)
                           (top height font-size-multiplier))
            font-height (* font-height font-size-multiplier)
            font-width (* font-width font-size-multiplier)]
        (doto vim
          (v/set-window-width (/ width font-width))
          (v/set-window-height (/ (max 0 text-height) font-height)))))))

(defn update-window-size! [{:keys [::c/vim] :as game}]
  (let [width (utils/get-width game)
        height (utils/get-height game)]
    (set-window-size! vim @session/*session width height))
  nil)

(defn ready-to-append? [session vim output]
  (and (seq output)
       (= 'NORMAL (v/get-mode vim))
       (not= :repl-out (:id (session/get-current-tab session)))))

(defn apply-parinfer! [session vim]
  (let [current-buffer (session/get-current-buffer session)
        {:keys [parsed-code needs-parinfer?]} (session/get-buffer session {:?id current-buffer})]
    (when needs-parinfer?
      (let [cursor-line (v/get-cursor-line vim)
            cursor-column (v/get-cursor-column vim)
            diffs (par/diff parsed-code)]
        (when (seq diffs)
          (v/input vim "i")
          (doseq [{:keys [line column content action]} diffs]
            ;; move all the way to the first column
            (dotimes [_ (v/get-cursor-column vim)]
              (v/input vim "<Left>"))
            ;; move to the right line
            (let [line-diff (- line (dec (v/get-cursor-line vim)))]
              (cond
                (pos? line-diff)
                (dotimes [_ line-diff]
                  (v/input vim "<Down>"))
                (neg? line-diff)
                (dotimes [_ (* -1 line-diff)]
                  (v/input vim "<Up>"))))
            ;; move to the right column
            (dotimes [_ column]
              (v/input vim "<Right>"))
            ;; delete or insert characters
            (doseq [ch (seq content)]
              (case action
                :remove
                (v/input vim "<Del>")
                :insert
                (v/input vim (str ch)))))
          ;; go back to the original position
          (v/set-cursor-position vim cursor-line cursor-column)
          (v/input vim "<Esc>")))
      (swap! session/*session
             (fn [session]
               (-> session
                   (c/upsert-buffer {:id current-buffer
                                     :needs-parinfer? false})
                   (c/insert-buffer-refresh current-buffer)))))))

(defn read-text-resource [path]
  (-> path io/resource slurp str/split-lines))

(def current-year (.getYear (LocalDate/now)))
(def ascii-art {"smile" nil
                "intro" nil
                "cat" (LocalDate/of current-year 8 8)
                "usa" (LocalDate/of current-year 7 4)
                "christmas" (LocalDate/of current-year 12 25)})

(defn assoc-ascii [session constants ascii-name]
  (c/new-tab! (session/get-game session) session :files)
  (-> session
      (c/upsert-buffer (c/->ascii ascii-name constants (read-text-resource (str "ascii/" ascii-name ".txt"))))
      (c/update-vim {:ascii ascii-name})))

(defn dissoc-ascii [session ascii-name]
  (-> session
      (c/remove-buffer ascii-name)
      (c/update-vim {:ascii nil})))

(defn change-ascii [session constants s]
  (let [{:keys [command-text command-start]} (session/get-command session)
        {:keys [mode ascii]} (session/get-vim session)]
    (cond
      (and ascii *update-ui?*)
      (dissoc-ascii session ascii)
      (and (= mode 'COMMAND_LINE)
           (= s "<Enter>")
           (= command-start ":")
           (contains? ascii-art command-text))
      (assoc-ascii session constants command-text)
      :else
      session)))

(defn init-ascii! []
  (let [now (LocalDate/now)]
    (when-let [ascii (or (some (fn [[ascii date]]
                                 (when (= now date)
                                   ascii))
                               ascii-art)
                         "intro")]
      (swap! session/*session
             (fn [session]
               (assoc-ascii session (session/get-constants session) ascii))))))

(defn input [{:keys [command-text command-start command-completion] :as command} vim s]
  (if (= 'COMMAND_LINE (v/get-mode vim))
    (let [pos (v/get-command-position vim)]
      (case s
        "<Tab>"
        (when (and (= (count command-text) pos) command-completion)
          (let [first-part (or (some-> command-text (str/index-of " ") inc)
                               0)]
            (dotimes [_ (- (count command-text) first-part)]
              (v/input vim "<BS>"))
            (doseq [ch command-completion]
              (v/input vim (str ch)))))
        ("<Right>" "<Left>" "<Up>" "<Down>")
        nil
        (if (and (= s "!")
                 (not (seq (some-> command-text str/trim))))
          nil ;; disable shell commands for now
          (v/input vim s))))
    (v/input vim s)))

(defn update-buffer [session buffer-ptr cursor-line cursor-column]
  (if-let [buffer (session/get-buffer session {:?id buffer-ptr})]
    (-> session
        (c/upsert-buffer (assoc buffer :cursor-line cursor-line :cursor-column cursor-column))
        (c/insert-buffer-refresh buffer-ptr))
    session))

(defn update-cursor-position! [vim cursor-line cursor-column]
  (v/set-cursor-position vim (inc cursor-line) cursor-column)
  (swap! session/*session update-buffer (v/get-current-buffer vim) cursor-line cursor-column)
  nil)

(defn update-after-input [session vim s]
  (let [current-buffer (v/get-current-buffer vim)
        old-mode (:mode (session/get-vim session))
        mode (v/get-mode vim)
        cursor-line (dec (v/get-cursor-line vim))
        cursor-column (v/get-cursor-column vim)
        constants (session/get-constants session)
        session (change-ascii session constants s)
        session (c/update-vim session {:mode mode
                                       :message nil ;; clear any pre-existing message
                                       :visual-range (when (v/visual-active? vim)
                                                       (-> (v/get-visual-range vim)
                                                           (update :start-line dec)
                                                           (update :end-line dec)
                                                           (assoc :type (v/get-visual-type vim))))
                                       :highlights (mapv (fn [highlight]
                                                           (-> highlight
                                                               (update :start-line dec)
                                                               (update :end-line dec)))
                                                     (v/get-search-highlights vim 1 (v/get-line-count vim current-buffer)))})
        font-size (:size (session/get-font session))
        session (c/update-command session
                                  (if (= mode 'COMMAND_LINE)
                                    (-> (merge
                                          (session/get-command session)
                                          (c/command-text (v/get-command-text vim) (v/get-command-completion vim))
                                          (when (not= old-mode 'COMMAND_LINE)
                                            {:command-start s}))
                                        (c/assoc-command constants mode font-size (v/get-command-position vim)))
                                    (c/command-text nil nil)))]
    (update-buffer session current-buffer cursor-line cursor-column)))

(defn on-input [vim session s]
  (input (session/get-command session) vim s)
  (let [session (swap! session/*session update-after-input vim s)
        mode (:mode (session/get-vim session))]
    (when (and (= 'NORMAL mode)
               (not= s "u"))
      (apply-parinfer! session vim))))

(def ^:const max-line-length 100)

(defn on-bulk-input [vim s limit-line-length?]
  (v/execute vim "set paste") ;; prevents auto indent
  (doseq [ch s
          :when (not= ch \return)]
    (when (and limit-line-length?
               (>= (v/get-cursor-column vim) max-line-length))
      (v/input vim "<Enter>"))
    (v/input vim (str ch)))
  (v/execute vim "set nopaste")
  (swap! session/*session update-after-input vim s))

(defn repl-enter! [vim session {:keys [out out-pipe]}]
  (apply-parinfer! session vim)
  (let [buffer-ptr (v/get-current-buffer vim)
        lines (vec (for [i (range (v/get-line-count vim buffer-ptr))]
                     (v/get-line vim buffer-ptr (inc i))))
        text (str (str/join \newline lines) \newline)]
    (doseq [s ["g" "g" "d" "G"]]
      (on-input vim session s))
    (doto out
      (.write text)
      .flush)
    (doto out-pipe
      (.write text)
      .flush)))

(defn append-to-buffer! [{:keys [::c/vim] :as game} session input]
  (when-let [buffer (:buffer-id (session/get-tab session {:?id :repl-out}))]
    (let [current-buffer (v/get-current-buffer vim)
          cursor-line (v/get-cursor-line vim)
          cursor-column (v/get-cursor-column vim)
          _ (v/set-current-buffer vim buffer)
          line-count (v/get-line-count vim buffer)
          char-count (count (v/get-line vim buffer line-count))]
      (v/set-cursor-position vim line-count (dec char-count))
      (on-input vim session "a")
      (on-bulk-input vim input true)
      (v/set-cursor-position vim (v/get-cursor-line vim) 0) ;; fix the repl's horiz scroll position after printing output
      (on-input vim session "<Esc>")
      (v/set-current-buffer vim current-buffer)
      (v/set-cursor-position vim cursor-line cursor-column))))

(defn on-buf-enter [game vim buffer-ptr]
  (when *update-window?*
    (async/put! (::c/command-chan game) [:resize-window]))
  (let [path (v/get-file-name vim buffer-ptr)
        lines (vec (for [i (range (v/get-line-count vim buffer-ptr))]
                     (v/get-line vim buffer-ptr (inc i))))]
    (if path
      ;; create or update the buffer
      (let [file (java.io.File. path)
            file-name (.getName file)
            canon-path (.getCanonicalPath file)
            current-tab (or (some
                              (fn [[tab path]]
                                (when (= canon-path (-> path java.io.File. .getCanonicalPath))
                                  tab))
                              constants/tab->path)
                            :files)
            session @session/*session
            constants (session/get-constants session)
            existing-buffer (session/get-buffer session {:?id buffer-ptr})
            buffer (if (and existing-buffer (= (:lines existing-buffer) lines))
                     existing-buffer
                     (assoc (c/->buffer buffer-ptr constants path file-name lines current-tab)
                       :cursor-line (dec (v/get-cursor-line vim))
                       :cursor-column (v/get-cursor-column vim)))]
        (when *update-window?*
          (async/put! (::c/command-chan game) [:set-window-title (str file-name " - Paravim")]))
        (swap! session/*session
          (fn [session]
            (-> session
                (c/update-tab current-tab buffer-ptr)
                (c/update-current-tab current-tab)
                (c/upsert-buffer buffer)
                (c/insert-buffer-refresh buffer-ptr)))))
      (do
        (when *update-window?*
          (async/put! (::c/command-chan game) [:set-window-title "Paravim"]))
        ;; clear the files tab
        (swap! session/*session c/update-tab :files nil)))))

(defn on-buf-update [game vim buffer-ptr start-line end-line line-count-change]
  (let [first-line (dec start-line)
        last-line (+ (dec end-line) line-count-change)
        lines (vec (for [i (range first-line last-line)]
                     (v/get-line vim buffer-ptr (inc i))))]
    (swap! session/*session c/insert-buffer-update
           {:buffer-id buffer-ptr
            :lines lines
            :first-line first-line
            :line-count-change line-count-change})))

(defn on-buf-delete [buffer-ptr]
  (swap! session/*session c/remove-buffer buffer-ptr))

(defn ->vim []
  (doto (v/->vim)
    v/init
    (v/execute "set hidden")
    (v/execute "set noswapfile")
    (v/execute "set nobackup")
    (v/execute "set nowritebackup")
    (v/execute "set tabstop=2")
    (v/execute "set softtabstop=2")
    (v/execute "set shiftwidth=2")
    (v/execute "set expandtab")
    (v/execute "set hlsearch")
    (v/execute "set fileformats=unix,dos")
    (v/execute "filetype plugin indent on")))

(defn yank-lines [{:keys [start-line start-column end-line end-column]}]
  (let [session @session/*session
        current-buffer (session/get-current-buffer session)]
    (when-let [{:keys [lines]} (session/get-buffer session {:?id current-buffer})]
      (let [yanked-lines (subvec lines (dec start-line) end-line)
            end-line (dec (count yanked-lines))
            end-column (cond-> end-column
                               (and (pos? end-column)
                                    (< end-column (count (nth yanked-lines end-line))))
                               inc)]
        (-> yanked-lines
            (update end-line subs 0 end-column)
            (update 0 subs start-column))))))

(defn init [{:keys [::c/vim] :as game}]
  (v/set-on-quit vim (fn [buffer-ptr force?]
                       (System/exit 0)))
  (v/set-on-auto-command vim (fn [buffer-ptr event]
                               (case event
                                 EVENT_BUFENTER
                                 (when *update-ui?*
                                   (on-buf-enter game vim buffer-ptr))
                                 EVENT_BUFDELETE
                                 (on-buf-delete buffer-ptr)
                                 nil)))
  (v/set-on-buffer-update vim (partial on-buf-update game vim))
  (v/set-on-stop-search-highlight vim (fn []
                                        (async/put! (::c/command-chan game) [:update-vim {:show-search? false}])))
  (v/set-on-unhandled-escape vim (fn []
                                   (async/put! (::c/command-chan game) [:update-vim {:show-search? false}])))
  (v/set-on-yank vim (fn [yank-info]
                       (try
                         (when-let [lines (yank-lines yank-info)]
                           (async/put! (::c/command-chan game) [:set-clipboard-string (str/join \newline lines)]))
                         (catch Exception e (.printStackTrace e)))))
  (v/set-on-message vim (fn [{:keys [message]}]
                          (async/put! (::c/command-chan game) [:update-vim {:message message}])))
  (binding [*update-window?* false]
    (run! #(v/open-buffer vim (constants/tab->path %)) [:repl-in :repl-out :files]))
  (init-ascii!)
  (update-window-size! game))

