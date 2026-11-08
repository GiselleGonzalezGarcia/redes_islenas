(ns redes.client.lookup
  "Derived lookups over the Datahike db value (computed once per db value)."
  (:require [datahike.api :as d]
            [clojure.string :as str]))

(defn- col
  "eid → value for attribute a."
  [db a]
  (persistent! (reduce (fn [m dt] (assoc! m (:e dt) (:v dt))) (transient {}) (d/datoms db :aevt a))))

(defn- slug [s]
  (-> (.normalize (str s) "NFD") (str/replace #"[̀-ͯ]" "") str/lower-case
      (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn compute [db]
  (let [kind (col db :place/kind)
        parent (col db :place/parent)
        pname (col db :place/name)
        pid (col db :place/id)
        muni (fn [e] (case (kind e) :municipality e :locality (parent e) nil))
        origin (col db :person/origin)
        resid (col db :person/residence)
        voyage (col db :person/voyage)
        ship (col db :voyage/ship)
        persons-by (fn [m] (reduce-kv (fn [acc p pl] (if-let [mu (muni pl)] (update acc mu (fnil conj []) p) acc)) {} m))
        born (persons-by origin)
        lives (persons-by resid)
        munis (into #{} (concat (keys born) (keys lives)))
        voyages-by-ship (reduce-kv (fn [acc v s] (update acc (slug s) (fnil conj []) v)) {} ship)
        persons-by-voyage (reduce-kv (fn [acc p v] (update acc v (fnil conj []) p)) {} voyage)]
    {:munis (->> munis
                 (map (fn [m] {:eid m :id (pid m) :name (pname m) :island (some-> (parent m) pid)
                               :island-name (some-> (parent m) pname)
                               :born (count (born m)) :lives (count (lives m))}))
                 (sort-by :name) vec)
     :muni-by-id (into {} (map (fn [m] [(pid m) m])) munis)
     :born born
     :lives lives
     :ships (->> voyages-by-ship
                 (map (fn [[sl vs]] {:id sl :name (ship (first vs)) :voyages vs
                                     :n (reduce + (map #(count (persons-by-voyage %)) vs))}))
                 (sort-by :name) vec)
     :ship-by-id voyages-by-ship
     :persons-by-voyage persons-by-voyage
     :slug slug}))

(def lookup
  (let [cache (atom [nil nil])]
    (fn [db]
      (let [[k v] @cache]
        (if (identical? k db) v
            (let [v (compute db)] (reset! cache [db v]) v))))))
