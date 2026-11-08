(ns redes.pipeline.xlsx
  "Read the project workbook into plain maps."
  (:require [dk.ative.docjure.spreadsheet :as ss]
            [clojure.string :as str]))

(def workbook-path "data/raw/canarios_todos.xlsx")

(defn- cell-value [cell]
  (when cell
    (let [v (try (ss/read-cell cell) (catch Exception _ nil))]
      (cond
        (string? v) (let [s (str/trim v)] (when-not (str/blank? s) s))
        (double? v) (if (== v (Math/floor v)) (long v) v)
        :else v))))

(defn- sheet-rows
  "Rows of a sheet as vectors of n cell values (blank cells → nil)."
  [wb sheet-name n]
  (let [sheet (ss/select-sheet sheet-name wb)]
    (for [row (ss/row-seq sheet)
          :when row]
      (mapv #(cell-value (.getCell row (int %))) (range n)))))

(def personas-cols
  "Column index → key for sheet Personas (two columns share the header
  'Lugar de Nacimiento', hence explicit indices)."
  {0 :no 1 :surname-group 2 :given 3 :surname1 4 :surname2 5 :other-names 6 :other-surnames
   7 :sex 8 :civil 9 :juridical 10 :occupation 11 :religion 12 :spouse 13 :father 14 :mother
   15 :siblings 16 :children 17 :grandparents 18 :relatives 19 :social 20 :ethnic 21 :race-marker
   22 :title 23 :membership 24 :politics 25 :cause-of-death 26 :birth-year 27 :birth-place
   28 :birth-muni 29 :birth-province 30 :birth-island 31 :birth-country 32 :residence-year
   33 :residence 34 :marriage-date 35 :marriage-place 36 :death-date 37 :death-place
   38 :destination 39 :birth-date 40 :birth-place-literal 41 :baptism-date 42 :baptism-place
   43 :velacion-date 44 :velacion-place 45 :notes 46 :citation 47 :literal})

(def originales-cols
  {0 :no 1 :name 2 :natural 3 :vecino 4 :island 5 :civil 6 :age 7 :signature 8 :destination
   9 :year 10 :ship 11 :remarks 12 :family 13 :archive 14 :other 15 :pdf-page 16 :pdf-page2
   17 :printed-page 18 :row 19 :doubtful 20 :edge})

(defn- ->maps [rows cols]
  (for [r rows]
    (into {} (keep (fn [[i k]] (when-some [v (nth r i nil)] [k v]))) cols)))

(defn read-workbook
  ([] (read-workbook workbook-path))
  ([path]
   (let [wb (ss/load-workbook path)]
     {:personas   (->maps (rest (sheet-rows wb "Personas" 48)) personas-cols)
      :originales (->maps (rest (sheet-rows wb "Datos originales" 21)) originales-cols)})))
