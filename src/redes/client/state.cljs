(ns redes.client.state
  "Reactive state: execution context, router, database and UI signals."
  (:require [org.replikativ.spindel.core :as s]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.dom.router :as rtr :refer-macros [router]]))

(defonce context (s/create-execution-context))

(defn with-ctx [f] (binding [ec/*execution-context* context] (f)))

(def routes
  [["/explorar"     :explorar]
   ["/persona/:id"  :persona]
   ["/pueblos"      :pueblos]
   ["/pueblo/:id"   :pueblo]
   ["/barcos"       :barcos]
   ["/barco/:id"    :barco]
   ["/travesia"     :travesia]
   ["/datos"        :datos]
   ["/fuentes"      :fuentes]
   ["/acerca"       :acerca]
   ["/"             :inicio]])

(def base-path
  (let [el (.querySelector js/document "base")
        b (when el (.-pathname (js/URL. (.-href el))))]
    (if (and b (not= b "/")) (clojure.string/replace b #"/$" "") "")))

(defonce app-router
  (with-ctx #(router (mapv (fn [[path nm]] [(str base-path path) nm]) routes))))

(defonce db-signal (with-ctx #(s/signal nil)))          ; Datahike db value
(defonce index-signal (with-ctx #(s/signal nil)))       ; derived lookup structures
(defonce lang-signal
  (with-ctx #(s/signal (if (= "en" (try (.getItem js/localStorage "lang") (catch :default _ nil))) :en :es))))
(defonce search-signal (with-ctx #(s/signal {:q "" :page 0})))

(defn navigate! [& args]
  (with-ctx #(apply rtr/navigate! app-router args))
  (js/window.scrollTo 0 0))

(defn href [route params] (rtr/href app-router route params))

(defn toggle-lang! []
  (with-ctx
    (fn []
      (let [l (if (= :es @lang-signal) :en :es)]
        (try (.setItem js/localStorage "lang" (name l)) (catch :default _ nil))
        (set! (.-lang js/document.documentElement) (name l))
        (reset! lang-signal l)))))

(defn set-search! [q]
  (with-ctx #(reset! search-signal {:q q :page 0})))

(defn set-page! [p]
  (with-ctx #(swap! search-signal assoc :page p)))
