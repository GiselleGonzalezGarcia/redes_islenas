(ns redes.client.views.persona
  "A single record (ficha)."
  (:require [org.replikativ.spindel.core :as s :refer-macros [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.dom.elements :as el
             :refer-macros [div span h1 h2 p dl dt dd article]]
            [datahike.api :as d]
            [clojure.string :as str]
            [redes.client.state :as state]
            [redes.client.i18n :refer [t]]
            [redes.client.views.common :as c]))

(def pattern
  '[* {:person/origin [:place/id :place/name :place/kind {:place/parent [:place/id :place/name {:place/parent [:place/name]}]}]}
      {:person/residence [:place/id :place/name :place/kind {:place/parent [:place/id :place/name {:place/parent [:place/name]}]}]}
      {:person/island [:place/name]}
      {:person/destination [:place/name]}
      {:person/voyage [:voyage/ship :voyage/year {:voyage/from [:place/name]} {:voyage/to [:place/name]}]}
      {:person/source [:source/title :source/citation]}
      {:person/events [*]}])

(defn muni-id
  "Municipality id of a pulled place (itself or its parent)."
  [pl]
  (case (:place/kind pl)
    :municipality (:place/id pl)
    :locality (get-in pl [:place/parent :place/id])
    nil))

(defn place-link [pl text]
  (if-let [id (muni-id pl)]
    (c/link {} :pueblo {:id id} text)
    text))

(defn place-line [pl]
  (when pl
    (->> [pl (:place/parent pl) (get-in pl [:place/parent :place/parent])]
         (keep :place/name) distinct (str/join ", "))))

(def doubtful-field
  {"Apellidos y nombre" :name "Natural de" :origin "Vecino de" :residence "Estado" :civil
   "Edad" :age "Firma" :signed "Destino" :dest "Fecha" :year "Barco" :ship})

(defn ship-slug [s]
  (-> (.normalize (str s) "NFD") (str/replace #"[\u0300-\u036f]" "") str/lower-case
      (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn field [lang k v & [flag?]]
  (when (and v (not= "" v))
    [(dt {:key (str (name k) "-t")} (t lang k))
     (dd {:key (str (name k) "-d")} v
         (when flag? (span {:class "dudoso"} (t lang :f/dudoso))))]))

(defn persona-spin [id]
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          db (iv/get-new (track state/db-signal))]
      (cond
        (nil? db) (div {:class "pagina"} (p (t lang :cargando)))
        :else
        (let [pr (try (d/pull db pattern [:person/id id]) (catch :default _ nil))]
          (if-not (:person/id pr)
            (div {:class "pagina"} (p (t lang :no-encontrado))
                 (p (c/link {} :explorar {} (t lang :volver))))
            (let [dub (into #{} (keep doubtful-field) (:person/doubtful pr))
                  origin (or (place-line (:person/origin pr)) (:person/origin-text pr))
                  resid (or (place-line (:person/residence pr)) (:person/residence-text pr))
                  v (:person/voyage pr)]
              (article {:class "pagina ficha"}
                (p (c/link {} :explorar {} (t lang :volver)))
                (h1 (:person/name pr)
                    (when (:name dub) (span {:class "dudoso"} (t lang :f/dudoso))))
                (p {:class "sub"}
                  (str/join " · " (remove nil? [origin
                                                (some->> (:place/name (:person/destination pr)) (str "→ "))
                                                (:person/year pr)])))
                (dl {:class "campos"}
                  (field lang :col/origen (when origin (place-link (:person/origin pr) origin)) (:origin dub))
                  (field lang :col/vecino (when resid (place-link (:person/residence pr) resid)) (:residence dub))
                  (field lang :col/destino (or (:place/name (:person/destination pr)) (:person/destination-text pr)) (:dest dub))
                  (field lang :col/anio (:person/year pr) (:year dub))
                  (field lang :f/estado (some->> (:person/civil pr) name (keyword "civil") (t lang)) (:civil dub))
                  (field lang :f/edad (:person/age pr) (:age dub))
                  (field lang :f/nacimiento (or (:person/birth-date pr) (:person/birth-year pr)))
                  (field lang :f/firma (case (:person/signed pr) true (t lang :si) false (t lang :no) nil) (:signed dub))
                  (field lang :f/oficio (:person/occupation pr))
                  (field lang :f/barco (when v (c/link {} :barco {:id (ship-slug (:voyage/ship v))}
                                                        (str (:voyage/ship v) " (" (:voyage/year v) ")"))) (:ship dub))
                  (field lang :f/reclamo (:person/reclamo pr))
                  (field lang :f/grupo (when (some-> (:person/group-size pr) (> 1)) (:person/group-size pr)))
                  (field lang :f/cuno (:person/stamp pr))
                  (field lang :f/conyuge (:person/spouse pr))
                  (field lang :f/padres (some->> [(:person/father pr) (:person/mother pr)] (remove nil?) seq (str/join " y ")))
                  (field lang :f/hijos (:person/children pr))
                  (field lang :f/observaciones (:person/remarks pr))
                  (field lang :f/archivo ({:la-palma "La Palma" :tenerife "Tenerife" :gran-canaria "Gran Canaria"} (:person/archive pr))))
                (when-let [lit (:person/literal pr)]
                  (div (h2 (t lang :f/texto)) (p {:class "literal"} lit)))
                (when-let [n (:person/notes pr)]
                  (div (h2 (t lang :f/notas)) (p {:class "cita"} n)))
                (div
                  (h2 (t lang :f/fuente))
                  (p {:class "cita"} (get-in pr [:person/source :source/citation]))
                  (p {:class "cita"} (:person/citation pr)))))))))))
