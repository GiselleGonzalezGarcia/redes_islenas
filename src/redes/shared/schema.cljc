(ns redes.shared.schema
  "Datahike schema for Redes Isleñas — shared by the JVM pipeline and the browser.")

(defn- attr
  ([ident type] (attr ident type {}))
  ([ident type opts]
   (merge {:db/ident ident
           :db/valueType type
           :db/cardinality :db.cardinality/one}
          opts)))

(def ^:private many {:db/cardinality :db.cardinality/many})
(def ^:private ident {:db/unique :db.unique/identity})

(def schema
  [;; --- Person ---------------------------------------------------------------
   (attr :person/id :db.type/string ident)            ; "T-<pdfpage>-<row>" | "G-<no>"
   (attr :person/no :db.type/string)                  ; number in the source
   (attr :person/given :db.type/string)
   (attr :person/surname1 :db.type/string)
   (attr :person/surname2 :db.type/string)
   (attr :person/name :db.type/string)                ; display name
   (attr :person/name-raw :db.type/string)            ; as printed
   (attr :person/search :db.type/string)              ; folded search key
   (attr :person/surname-group :db.type/string)
   (attr :person/sex :db.type/keyword)                ; :m :f
   (attr :person/civil :db.type/keyword)              ; :single :married :widowed
   (attr :person/age :db.type/long)
   (attr :person/birth-year :db.type/long)            ; estimated (year - age) or recorded
   (attr :person/birth-date :db.type/string)
   (attr :person/signed :db.type/boolean)             ; signature: literacy proxy
   (attr :person/occupation :db.type/string)
   (attr :person/religion :db.type/string)
   (attr :person/spouse :db.type/string)
   (attr :person/father :db.type/string)
   (attr :person/mother :db.type/string)
   (attr :person/siblings :db.type/string)
   (attr :person/children :db.type/string)
   (attr :person/grandparents :db.type/string)
   (attr :person/race-marker :db.type/string)
   (attr :person/title :db.type/string)
   (attr :person/origin :db.type/ref)                 ; most specific birth place
   (attr :person/origin-text :db.type/string)         ; birth place as written
   (attr :person/residence :db.type/ref)              ; vecino de
   (attr :person/residence-text :db.type/string)
   (attr :person/island :db.type/ref)                 ; island of origin (birth, else residence)
   (attr :person/home :db.type/ref)                   ; best home place: birth, else residence, else stamp municipality
   (attr :person/destination :db.type/ref)            ; destination country/region place
   (attr :person/destination-text :db.type/string)
   (attr :person/year :db.type/long)                  ; year of the record / departure
   (attr :person/voyage :db.type/ref)
   (attr :person/group-size :db.type/long)            ; "Otros datos fami"
   (attr :person/reclamo :db.type/string)             ; who sent for them (padre, hermano…)
   (attr :person/stamp :db.type/string)               ; "Cuño de X"
   (attr :person/remarks :db.type/string)             ; "Otras características" as printed
   (attr :person/archive :db.type/keyword)            ; Localización: :la-palma :tenerife :gran-canaria
   (attr :person/notes :db.type/string)               ; Bio, Info, Notas
   (attr :person/citation :db.type/string)            ; Fuentes
   (attr :person/literal :db.type/string)             ; Texto_Literal
   (attr :person/doubtful :db.type/string many)       ; fields read with doubt
   (attr :person/source :db.type/ref)
   (attr :person/pdf-page :db.type/long)              ; left half page
   (attr :person/pdf-page2 :db.type/long)             ; right half page
   (attr :person/printed-page :db.type/long)
   (attr :person/row :db.type/long)
   (attr :person/events :db.type/ref (merge many {:db/isComponent true}))

   ;; --- Event (genealogical records) ----------------------------------------
   (attr :event/type :db.type/keyword)                ; :baptism :marriage :death :birth
   (attr :event/date :db.type/string)
   (attr :event/year :db.type/long)
   (attr :event/place-text :db.type/string)
   (attr :event/place :db.type/ref)

   ;; --- Place ------------------------------------------------------------------
   (attr :place/id :db.type/string ident)
   (attr :place/name :db.type/string)
   (attr :place/kind :db.type/keyword)                ; :locality :municipality :island :province :country :port :region
   (attr :place/parent :db.type/ref)
   (attr :place/lat :db.type/double)
   (attr :place/lon :db.type/double)
   (attr :place/wikidata :db.type/string)

   ;; --- Voyage -----------------------------------------------------------------
   (attr :voyage/id :db.type/string ident)            ; "<ship>|<year>|<archive>"
   (attr :voyage/ship :db.type/string)
   (attr :voyage/year :db.type/long)
   (attr :voyage/archive :db.type/keyword)
   (attr :voyage/from :db.type/ref)                   ; departure port (place)
   (attr :voyage/to :db.type/ref)                     ; arrival port (place)

   ;; --- Source -----------------------------------------------------------------
   (attr :source/id :db.type/string ident)
   (attr :source/title :db.type/string)
   (attr :source/kind :db.type/keyword)
   (attr :source/citation :db.type/string)])
