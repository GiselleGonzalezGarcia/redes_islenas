(ns redes.client.i18n
  "Interface strings, ES (default) and EN.")

(def strings
  {:nav/inicio      {:es "Inicio" :en "Home"}
   :nav/explorar    {:es "Buscar personas" :en "Find people"}
   :nav/travesia    {:es "La travesía" :en "The crossing"}
   :nav/datos       {:es "Datos" :en "Data"}
   :nav/fuentes     {:es "Fuentes" :en "Sources"}
   :nav/acerca      {:es "Acerca de" :en "About"}
   :idioma          {:es "English" :en "Español"}

   :hero/entrada    {:es "Más de dieciséis mil canarios que cruzaron el Atlántico entre los siglos XV y XX: de qué pueblos salieron, en qué barcos viajaron y adónde llegaron."
                     :en "More than sixteen thousand Canary Islanders who crossed the Atlantic between the fifteenth and the twentieth century: the villages they left, the ships they sailed on and where they arrived."}
   :busca/placeholder {:es "Apellido, nombre o pueblo" :en "Surname, given name or village"}
   :busca/boton     {:es "Buscar" :en "Search"}
   :busca/resultados {:es "registros" :en "records"}
   :busca/anterior  {:es "Anterior" :en "Previous"}
   :busca/siguiente {:es "Siguiente" :en "Next"}
   :busca/pagina    {:es "Página" :en "Page"}
   :busca/vacio     {:es "Ningún registro coincide. Prueba solo con el primer apellido o sin tildes." :en "No record matches. Try the first surname only, or leave out accents."}

   :cifra/personas  {:es "personas en el registro" :en "people in the register"}
   :cifra/pueblos   {:es "pueblos de origen localizados" :en "home villages located"}
   :cifra/barcos    {:es "travesías con nombre de barco" :en "voyages with a named ship"}
   :cifra/anios     {:es "años cubiertos" :en "years covered"}

   :col/nombre      {:es "Nombre" :en "Name"}
   :col/origen      {:es "Natural de" :en "Born in"}
   :col/vecino      {:es "Vecino de" :en "Resident of"}
   :col/destino     {:es "Destino" :en "Destination"}
   :col/anio        {:es "Año" :en "Year"}
   :col/edad        {:es "Edad" :en "Age"}

   :f/estado        {:es "Estado civil" :en "Marital status"}
   :f/edad          {:es "Edad declarada" :en "Stated age"}
   :f/nacimiento    {:es "Nacimiento (aprox.)" :en "Born (approx.)"}
   :f/firma         {:es "Firma" :en "Signed"}
   :f/barco         {:es "Barco" :en "Ship"}
   :f/archivo       {:es "Registrado en" :en "Registered in"}
   :f/reclamo       {:es "Reclamado por / se une a" :en "Sent for by / joining"}
   :f/cuno          {:es "Cuño (sello municipal)" :en "Stamp (municipal seal)"}
   :f/grupo         {:es "Viajan juntos" :en "Travelling together"}
   :f/observaciones {:es "Observaciones en la fuente" :en "Remarks in the source"}
   :f/notas         {:es "Notas" :en "Notes"}
   :f/fuente        {:es "Fuente" :en "Source"}
   :f/oficio        {:es "Oficio" :en "Occupation"}
   :f/conyuge       {:es "Cónyuge" :en "Spouse"}
   :f/padres        {:es "Padres" :en "Parents"}
   :f/hijos         {:es "Hijos" :en "Children"}
   :f/texto         {:es "Texto literal" :en "Literal text"}
   :f/dudoso        {:es "lectura dudosa" :en "uncertain reading"}
   :si              {:es "Sí" :en "Yes"}
   :no              {:es "No" :en "No"}
   :civil/single    {:es "Soltero/a" :en "Single"}
   :civil/married   {:es "Casado/a" :en "Married"}
   :civil/widowed   {:es "Viudo/a" :en "Widowed"}
   :volver          {:es "Volver a la búsqueda" :en "Back to search"}
   :cargando        {:es "Cargando los registros…" :en "Loading the records…"}
   :no-encontrado   {:es "No hay ningún registro con ese identificador." :en "There is no record with that identifier."}

   :tr/titulo       {:es "La travesía" :en "The crossing"}
   :tr/play         {:es "Reproducir" :en "Play"}
   :tr/pausa        {:es "Pausa" :en "Pause"}
   :tr/anio         {:es "Año" :en "Year"}
   :tr/velocidad    {:es "Velocidad" :en "Speed"}
   :tr/lenta        {:es "Lenta" :en "Slow"}
   :tr/normal       {:es "Normal" :en "Normal"}
   :tr/rapida       {:es "Rápida" :en "Fast"}
   :tr/barcos       {:es "Barcos con nombre" :en "Named ships"}
   :tr/personas     {:es "Emigrantes sin barco registrado" :en "Emigrants with no recorded ship"}
   :tr/rutas        {:es "Rutas" :en "Routes"}
   :tr/pueblos      {:es "Pueblos de origen" :en "Home villages"}
   :tr/islas        {:es "Ver las islas" :en "Show the islands"}
   :tr/atlantico    {:es "Ver el Atlántico" :en "Show the Atlantic"}
   :tr/en-mar       {:es "En el mar" :en "At sea"}
   :tr/nadie        {:es "Ningún barco con nombre en el mar en este momento." :en "No named ship at sea right now."}
   :tr/pasajeros    {:es "pasajeros registrados" :en "registered passengers"}
   :tr/salida       {:es "salida" :en "departed"}
   :tr/nota         {:es "Cada punto sale de su pueblo de origen hacia el puerto de su isla y, de allí, cruza el océano o sube a su barco. Las rutas y los días son ilustrativos: la fuente indica el año del registro, no la fecha del viaje. Los barcos que llegan a Cuba se dibujan hasta La Habana."
                     :en "Each dot leaves its home village for the port of its island and from there crosses the ocean or boards its ship. Routes and days are illustrative: the source gives the year of the record, not the date of sailing. Ships bound for Cuba are drawn to Havana."}
   :pie/texto       {:es "Redes Isleñas reúne la Relación de emigrantes canarios compilada por Francisco Hernández Delgado (Archivo Histórico de Teguise) y registros genealógicos cubanos. Datos abiertos; las lecturas dudosas de la fuente impresa se señalan en cada registro."
                     :en "Redes Isleñas brings together the list of Canarian emigrants compiled by Francisco Hernández Delgado (Archivo Histórico de Teguise) and Cuban genealogical records. Open data; uncertain readings of the printed source are flagged on each record."}})

(defn t [lang k]
  (get-in strings [k lang] (get-in strings [k :es] (str k))))
