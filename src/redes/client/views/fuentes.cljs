(ns redes.client.views.fuentes
  "Sources, method, glossary, further reading; and the About page."
  (:require [org.replikativ.spindel.core :as s :refer-macros [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel.dom.elements :as el
             :refer-macros [div h1 h2 h3 p a dl dt dd ul li section article span]]
            [redes.client.state :as state]))

(defn- ext [href label] (a {:href href :rel "noopener" :target "_blank"} label))

(def glosario
  [["Natural de" "Lugar de nacimiento tal como figura en la fuente. Lo hemos situado en el municipio o la localidad actual cuando la identificación es inequívoca."
    "Place of birth as written in the source, located in today's municipality or locality where the identification is unambiguous."]
   ["Vecino de" "Lugar de residencia en el momento del registro." "Place of residence at the time of the record."]
   ["Estado" "S: soltero/a; C: casado/a; V: viudo/a." "S: single; C: married; V: widowed."]
   ["Edad" "Edad declarada. El año de nacimiento que mostramos es aproximado: año del registro menos la edad. Una edad 0 en la fuente suele indicar que no se anotó."
    "Stated age. The year of birth we show is approximate: year of the record minus age. An age of 0 in the source usually means it was not noted."]
   ["Firma" "Si la persona firmó el documento. Es una medida aproximada de alfabetización." "Whether the person signed the document; a rough measure of literacy."]
   ["Cuño" "Sello del ayuntamiento que expidió el documento («Cuño de Mazo»). Sirve para situar el municipio de procedencia cuando falta el lugar de nacimiento."
    "Stamp of the town council that issued the document (‘Cuño de Mazo’). It locates the home municipality when the place of birth is missing."]
   ["Reclamo / unirse" "El emigrante viajaba llamado por un familiar ya instalado en América («reclamo padre») o para reunirse con él («unirme hermana»)."
    "The emigrant travelled because a relative already settled in the Americas sent for them (‘reclamo padre’) or to join them (‘unirme hermana’)."]
   ["Otros datos familiares" "Número de personas incluidas en el mismo registro (por ejemplo, una madre con sus hijos)."
    "Number of people included in the same record (for example, a mother with her children)."]
   ["Localización" "Isla en cuya documentación se encontró el registro: La Palma, Tenerife o Gran Canaria. No es necesariamente la isla de nacimiento."
    "Island in whose records the entry was found: La Palma, Tenerife or Gran Canaria. Not necessarily the island of birth."]
   ["Otros datos" "Número interno de la compilación; su significado no está documentado." "Internal number of the compilation; its meaning is not documented."]])

(def lecturas
  [["Archivo Histórico de Teguise" "https://archivoteguise.es"
    "Archivo municipal que conserva la compilación y publica estudios sobre la emigración lanzaroteña."
    "Municipal archive that holds the compilation and publishes studies on emigration from Lanzarote."]
   ["Memoria Digital de Canarias (ULPGC)" "https://mdc.ulpgc.es"
    "Biblioteca digital con buena parte de la bibliografía canaria sobre emigración, incluidas las actas de los Coloquios de Historia Canario-Americana."
    "Digital library with much of the Canarian literature on emigration, including the proceedings of the Coloquios de Historia Canario-Americana."]
   ["Jable, archivo de prensa digital (ULPGC)" "https://jable.ulpgc.es"
    "Prensa canaria del siglo XIX, donde se anunciaban las salidas de barcos hacia América."
    "Nineteenth-century Canarian press, where sailings to the Americas were announced."]
   ["Movimientos Migratorios Iberoamericanos (PARES)" "https://pares.cultura.gob.es/MovimientosMigratorios/"
    "Portal de los archivos estatales con registros de emigrantes y pasajeros."
    "State archives portal with records of emigrants and passengers."]
   ["Memoria Digital de Lanzarote" "https://memoriadelanzarote.com"
    "Fotografías, películas y documentos de Lanzarote, con artículos sobre la emigración de la isla."
    "Photographs, films and documents from Lanzarote, with articles on emigration from the island."]
   ["Gaceta de Madrid (BOE, colección histórica)" "https://www.boe.es/buscar/gazeta.php"
    "Las disposiciones oficiales del siglo XIX sobre emigración y pasaportes."
    "Nineteenth-century official regulations on emigration and passports."]])

(def bibliografia
  ;; references and links as given by the project team (data/raw/Fuentes.odt)
  [["Hernández Delgado, Francisco. 2002. Relación de emigrantes canarios: Clasificados por orden alfabético. Vol. I-III, Archivo Histórico Municipal de Teguise, Lanzarote, Spain."
    "https://geneasud.blogspot.com/2023/03/relacion-de-emigrantes-canarios-cuba_30.html"]
   ["Libro de Barajas: Matrimonios, 1584–1622. Archivo de la Sagrada Catedral de La Habana, Cuba. Transcribed by Jorge E. Delgadillo Núñez. Slave Societies Digital Archive, Vanderbilt University, 2016."
    "https://drive.google.com/file/d/1YuApv5COHW6VmYXnTvySgLMvN4_cqx4h/view"]
   ["Libros de Matrimonios de Remedios. Transcribed by José Antonio Mahy, Cuban Genealogy Club of Miami, 2009."
    "https://www.cubangenclub.org/wp-content/uploads/2021/03/Church_Remedios_Marriages.pdf"]
   ["Navarrete, William, and Manuel F. Díaz Álvarez. 2023. Primeras Familias, Poblado y Ciudad San Isidoro de Holguín: Estudio de Genealogía Cubana. Primera edición. [Miami, Florida]: UnosOtrosEdiciones."
    nil]
   ["Santa Cruz y Mallén, Francisco Xavier de. 1940-1988, Historia de Familias Cubanas, 1-9 Vols., La Habana: “Editorial Hércules”."
    "https://archive.org/search?query=creator%3A%22Santa+Cruz+y+Mallon%2C+Francisco+Xavier+de%22"]])

(defn fuentes-spin []
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          es? (= lang :es)
          L (fn [es en] (if es? es en))]
      (article {:class "pagina texto"}
        (h1 (L "Las fuentes" "The sources"))
        (p {:class "intro"}
          (L "Toda base de datos es una lectura de sus fuentes. Aquí explicamos de dónde vienen los registros, cómo los hemos transcrito y qué no pueden decirnos."
             "Every database is a reading of its sources. Here we explain where the records come from, how we transcribed them and what they cannot tell us."))

        (section
          (h2 (L "La Relación de emigrantes canarios" "The list of Canarian emigrants"))
          (p (L "La mayor parte de los registros (15.298) procede de la Relación de emigrantes canarios: Clasificados por orden alfabético, compilada por Francisco Hernández Delgado, cronista oficial de Teguise y fundador del Archivo Histórico de Teguise. Se conserva impresa en tres tomos ordenados alfabéticamente (A–F, F–M y M–S), salidos de una base de datos el 28 de enero de 2002."
                "Most records (15,298) come from the Relación de emigrantes canarios: Clasificados por orden alfabético, a list compiled by Francisco Hernández Delgado, official chronicler of Teguise and founder of the Archivo Histórico de Teguise. It survives as three printed volumes in alphabetical order (A–F, F–M and M–S), printed from a database on 28 January 2002."))
          (p (L "Según su compilador, la relación reúne datos de documentación de distintos ayuntamientos y del antiguo Museo del Emigrante de Teguise; no es una serie de archivo original. Cada entrada anota nombre, naturaleza, vecindad, estado, edad, firma, destino y año, y a veces el barco, el sello municipal o el familiar que reclamaba al emigrante."
                "According to its compiler, the list brings together data from the records of several town councils and the former Museo del Emigrante in Teguise; it is not an original archival series. Each entry notes name, birthplace, residence, marital status, age, signature, destination and year, and sometimes the ship, the municipal stamp or the relative who sent for the emigrant."))
          (p (L "Los tomos terminan en la letra S: los apellidos de la T a la Z no forman parte de la compilación conservada. Apellidos tan comunes como Torres, Trujillo o Vega faltan por ello."
                "The volumes end at the letter S: surnames from T to Z are not part of the surviving compilation. Common surnames such as Torres, Trujillo or Vega are missing for that reason.")))

        (section
          (h2 (L "Cómo la hemos transcrito" "How we transcribed it"))
          (p (L "Los tomos impresos se escanearon y se leyeron por reconocimiento óptico de caracteres. Cada página impresa contiene la mitad izquierda de la tabla y la siguiente la mitad derecha; las unimos fila a fila. Las lecturas dudosas se señalan en cada ficha, y estamos repitiendo la lectura columna por columna para corregir errores."
                "The printed volumes were scanned and read by optical character recognition. Each printed page holds the left half of the table and the next one the right half; we joined them row by row. Uncertain readings are flagged on each record, and we are re-reading the scans column by column to correct errors."))
          (p (L "Hemos conservado las grafías de los nombres y no hemos completado letras cortadas por el ancho de la columna impresa. Los lugares se sitúan en los municipios actuales con fines de búsqueda y mapa, sin afirmar que fueran las divisiones del siglo XIX."
                "We kept the spelling of names and did not complete letters cut off by the width of the printed column. Places are located in today's municipalities for search and mapping, without claiming these were the nineteenth-century divisions.")))

        (section
          (h2 (L "Otras fuentes" "Other sources"))
          (p (L "Unos 700 registros proceden de genealogías cubanas y de libros parroquiales: la Historia de Familias Cubanas de Francisco Xavier de Santa Cruz y Mallén, el estudio de William Navarrete y Manuel F. Díaz Álvarez sobre las primeras familias de San Isidoro de Holguín, el Libro de Barajas de matrimonios de la Catedral de La Habana (1584–1622) y los libros de matrimonios de Remedios. Cada ficha cita su fuente."
                "About 700 records come from Cuban genealogies and parish books: the Historia de Familias Cubanas by Francisco Xavier de Santa Cruz y Mallén, the study by William Navarrete and Manuel F. Díaz Álvarez of the first families of San Isidoro de Holguín, the Libro de Barajas of marriages at Havana Cathedral (1584–1622) and the marriage books of Remedios. Each record cites its source.")))

        (section
          (h2 (L "Fuentes citadas" "Sources cited"))
          (ul {:class "bibliografia"}
            (map-indexed (fn [i [ref href]]
                           (li {:key (str i)} ref
                               (when href (span " " (ext href (if es? "Consultar" "View"))))))
                         bibliografia)))

        (section
          (h2 (L "Cómo leer un registro" "How to read a record"))
          (dl {:class "glosario"}
            (mapcat (fn [i [term es en]]
                      (list (dt {:key (str "t" i)} term) (dd {:key (str "d" i)} (if es? es en))))
                    (range) glosario)))

        (section
          (h2 (L "Para saber más" "Further reading"))
          (ul {:class "lecturas"}
            (map-indexed (fn [i [label href es en]]
                           (li {:key (str i)} (ext href label) (p (if es? es en))))
                         lecturas)))))))

(def equipo
  [[{:es "Dra. Giselle González García" :en "Dr. Giselle González García"} "Investigadora Principal y Recopiladora de la Base de Datos" "Principal Investigator and Database Compiler"]
   ["José Antonio González Marrero" "Investigador y Genealogista Especializado" "Researcher and Specialist Genealogist"]
   ["Mirelis Pedraza" "Investigadora y Genealogista Especializada" "Researcher and Specialist Genealogist"]
   ["Lourdes del Pino" "Investigadora y Genealogista Especializada" "Researcher and Specialist Genealogist"]
   ["Samuel Martínez Alcalá" "Investigador y Genealogista Especializado" "Researcher and Specialist Genealogist"]])

(defn acerca-spin []
  (spin
    (let [lang (iv/get-new (track state/lang-signal))
          es? (= lang :es)
          L (fn [es en] (if es? es en))]
      (article {:class "pagina texto"}
        (h1 (L "Acerca de Redes Isleñas" "About Redes Isleñas"))
        (p {:class "intro"}
          (L "Redes Isleñas es un proyecto colaborativo abierto de historia pública sobre la emigración canaria a América que abarca de los siglos XV al XX. Este proyecto se centra en una recopilación de datos biográficos, demográficos y genealógicos de los migrantes para entender sus redes familiares y otras tendencias que definen sus vidas a ambos lados del Océano Atlántico."
             "Redes Isleñas is an open, collaborative public-history project on Canarian emigration to the Americas from the fifteenth to the twentieth century. It centres on a compilation of biographical, demographic and genealogical data on the migrants, in order to understand their family networks and other patterns that shaped their lives on both sides of the Atlantic Ocean."))
        (section
          (h2 (L "Proyecto hermano" "Sister project"))
          (p (L "Redes Isleñas nace de la misma metodología que se implementó para crear la base de datos sobre la emigración irlandesa a Cuba y el Caribe Hispano, titulada «The Cuba-Ireland Immigration Database». Ambos proyectos comparten preguntas sobre la llegada, recepción, y organización de trabajadores libres en Cuba y las redes sociales que los sostenían."
                "Redes Isleñas grows out of the same methodology used to build the database on Irish emigration to Cuba and the Hispanic Caribbean, ‘The Cuba-Ireland Immigration Database’. Both projects share questions about the arrival, reception and organisation of free workers in Cuba and the social networks that sustained them."))
          (p (a {:href "https://gisellegonzalezgarcia.github.io/ciid/" :rel "noopener" :class "descarga"}
                "Cuba-Ireland Immigration Database")))
        (section
          (h2 (L "Equipo y colaboraciones" "Team and partners"))
          (dl {:class "glosario equipo"}
            (mapcat (fn [i [nombre es en]]
                      (list (dt {:key (str "n" i)} (if (map? nombre) (nombre lang) nombre))
                            (dd {:key (str "r" i)} (if es? es en))))
                    (range) equipo)))
        (section
          (h2 (L "Datos abiertos" "Open data"))
          (p (L "Los datos que aquí se publican son de libre uso y descarga, con la debida citación y referencia al equipo de trabajo y sus resultados."
                "The data published here are free to use and download, with due citation of and reference to the team and its work."))
          (p (a {:href (str state/base-path "/data/redes-islenas.csv") :download "redes-islenas.csv" :class "descarga"}
                (L "Descargar todos los registros (CSV)" "Download all records (CSV)"))))
        (section
          (h2 (L "Cómo está hecho" "How it is made"))
          (p (L "Base de datos Datahike, interfaz Spindel, mapas con MapLibre y deck.gl, gráficos con Observable Plot. Costas de Natural Earth; coordenadas de los pueblos de Wikidata. Todo el código es abierto."
                "Datahike database, Spindel interface, maps with MapLibre and deck.gl, charts with Observable Plot. Coastlines from Natural Earth; village coordinates from Wikidata. All code is open source.")))))))
