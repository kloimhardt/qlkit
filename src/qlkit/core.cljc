(ns qlkit.core
  (:require #?@(:cljs [[react-dom :refer [render]]
                       [react :refer [createElement]]
                       [create-react-class :refer [createReactClass]]])
            [qlkit.dom :as dom]            
            [qlkit.spec :as spec]
            [clojure.string :as st]))

#?(:clj
   (defmacro defcomponent [nam & bodies]
     "This macro lets you declare a component class. It can contain the sections of state, query, render, component-did-mount and/or component-will-unmount. It will define a name, which can be directly referenced in render functions to embed nested qlkit components."
     (doseq [[nam] bodies]
       (when-not ('#{state query render component-did-mount component-will-unmount} nam)
         (throw (ex-info (str "Unknown component member " nam) {}))))
     `(let [key# (keyword ~(str (:name (:ns &env))) ~(name nam))]
        (def ~nam key#)
        (add-class key#
                   ~(into {}
                          (for [[nam & more :as body] bodies]
                            (if ('#{state query} nam)
                              [(keyword nam)
                               (last more)]
                              [(keyword nam)
                               `(fn ~(first more)
                                  ~@(rest more))])))))))

(def mount-info (atom {}))

(defn- actualize [x]
  "This function makes sure all shallow lazy sequences are expanded, IF there is a lazy sequence."
  (if (seq? x)
    (doall x)
    x))

(defn- parse-query-term [query-term env]
  "Parses a single query term, i.e. something in the form [:person {} [:person/name] [:person/age]]. The environment is used to pass info from parent queries down to child queries."
  (let [{:keys [state parsers]}      @mount-info
        {:keys [read remote mutate]} parsers
        key                          (first query-term)
        read-fun                     (get-method read key)
        mutate-fun                   (get-method mutate key)
        remote-fun                   (when remote
                                       (get-method remote key))]
    (when (not (or read-fun remote-fun mutate-fun))
      (throw (ex-info (str "no parser for " key) {})))
    (actualize (cond mutate-fun (if state
                                  (mutate-fun query-term env state)
                                  (mutate-fun query-term env))
                     read-fun   (if state
                                  (read-fun query-term env @state)
                                  (read-fun query-term env))
                     :else      nil))))

(defn parse-query
  "Parses an entire query, i.e. something with multiple query terms, such as [[:person {} [:person/name]] [:widget {} [:widget/name]]]. The output of 'parse-query' is meant to be sent by the server to the client to pass back query results."
  ([query env]
   (doall (for [query-term query]
            (parse-query-term query-term env))))
  ([query]
   (parse-query query {})))

(defn- parse-query-into-map [query env]
  "This parses a query so the results are in nested maps for easy access. This is used for all internal query parsing in cases where there are unique keys for query terms, which is true except for the root query returned by 'transact!', which can have duplicate keys in order to guarantee side-effect order."
  (into {::env env}
        (map vector (map first query) (parse-query query env))))

(defn parse-children [query-term env]
  "Takes a query and the environment and triggers all children. The key goal here is that environmental values passed to children need to be marked with the parent query they originated from, to aid in generating a well-formed remote query."
  (parse-query-into-map (drop 2 query-term) (assoc env ::parent-env (assoc env ::query-key (first query-term)))))

(defn- camel-case [s]
  "Convert a shishkabob string to camelcase"
  (let [words (st/split s #"-")]
    (apply str
           (first words)
           (for [word (rest words)]
             (str (st/upper-case (first word)) (subs word 1))))))

(defn- camel-case-keys [kv-map]
  "CamelCases all the keys, but only if they are keywords."
  (into {}
        (for [[k v] kv-map]
          [(if (keyword? k)
             (keyword (camel-case (name k)))
             k)
           v])))

(def classes (atom {}))

(def ^:dynamic *this* nil)

(declare react-class)

(defn- splice-in-seqs [coll]
  "Supports 'seq splicing' and 'nil eliding'... i.e. converts [:foo (list :bar :baz)] into [:foo :bar :baz] and [:foo nil :baz] into [:foo :baz]"
  (reduce (fn [acc item]
            (cond (seq? item) (vec (concat acc (splice-in-seqs item)))
                  item        (conj acc item)
                  :else       acc))
          []
          coll))

(defn- normalize-query [query]
  "Splices in seqs recursively, and also puts in missing empty attribute lists."
  (for [item (splice-in-seqs query)]
    (let [maybe-params  (second item)
          [nam params children] (if (and maybe-params (and (not (vector? maybe-params)) (not (seq? maybe-params))))
                                  [(first item) (second item) (drop 2 item)]
                                  [(first item) {} (rest item)])]
      (apply vector
             nam
             params
             (normalize-query children)))))

(defn- add-class [nam class]
  "Adds a qlkit class to the global list of classes. Note that a 'qlkit class' is just a clojure map."
  (assert (:render class))
  (when-let [query (:query class)]
    (try (spec/query-spec (vec (normalize-query query)) :reject-mutations)
         (catch
             #?(:clj Exception
                :cljs :default)
           e
             #?(:cljs (println (str "component-helper parse error in " nam)))
           (throw e))))
  (swap! classes
         assoc
         nam
         (cond-> class
           (:query class) (update :query normalize-query)
           #?(:cljs true
              :clj false) (assoc ::react-class (react-class class)))))

(defn- fix-event-references [this props]
  "This function decouples events from using the traditional javascript 'this' context into something that can be managed in a more clojure-y way."
  (into {}
        (for [[k v] props]
          (if (fn? v)
            [k (fn [& args]
                 (binding [*this* this]
                   (apply v args)))]
            [k v]))))

(defn- fix-classname [props]
  "React doesn't permit the standard html 'class' property, this function reenables it when using qlkit."
  (if (contains? props :class)
    (-> props
        (dissoc :class)
        (assoc :className (:class props)))
    props))

(defn get-query [key]
  "Returns the query for a class. Note that in qlkit, queries are not changed at runtime and hence just retrieved at the class-level."
  (:query (@classes key)))

(defn- mutation-query-term? [query-term]
  "Indicates if the mutation term is a mutation- Note that this is a check for a 'shallow' mutation, by design subterms could still be a mutation."
  (= \! (last (name (first query-term)))))

(defn- parse-query-remote [query]
  "This parses a query and sends off its parts to any 'remote' query handlers. Returns another query (the query to send to the server) as a result."
  (normalize-query (reduce (fn [acc item]
                             (let [{:keys [state parsers]} @mount-info
                                   {:keys [remote]}        parsers
                                   key                     (first item)
                                   remote-fun              (get-method remote key)]
                               (if remote-fun
                                 (conj acc (remote-fun item @state))
                                 acc)))
                           []
                           query)))

(defn parse-children-remote [[dispatch-key params & chi :as query]]
  "This is a function you can use within a remote query parser to iteratively execute the children of the query."
  (let [chi-remote (parse-query-remote chi)]
    (when (seq chi-remote)
      (vec (concat [dispatch-key params] chi-remote)))))

(defn- parse-query-term-sync [[key :as query-term] result env]
  "Calls the sync parsers for a query term, which are responsible for merging server results into the client state."
  (let [sync-fun (get-method (:sync (:parsers @mount-info)) key)]
    (cond sync-fun                          (actualize (sync-fun query-term result env (:state @mount-info)))
          (mutation-query-term? query-term) nil
          :else                             (throw (ex-info (str "Missing sync parser for " key) {})))))

(defn parse-children-sync [query-term result env]
  "This function can be called from sync parsers to recursively perform child sync queries."
  (doseq [[key :as child-query-term] (drop 2 query-term)]
    (parse-query-term-sync child-query-term (result key) env)))

(defn- map-delta [map1 map2]
  "Finds the minimal X such that (merge map1 X) = (merge map1 map2)"
  (into {}
        (filter (fn [[k v]]
                  (not= v (map1 k)))
                map2)))
             
(defn- root-query [env query]
  "Takes a query that is relative to a component in the hierarchy and converts it into a query at the root level. Note that each term in the original query will be given its own 'root' in the resulting query, which is done to control ordering of side effects."
  (for [query-term query]
    (loop [query-term query-term
           env        (::parent-env env)]
      (if env
        (let [parent-env (::parent-env env)]
          (recur [(::query-key env)
                  (dissoc (if parent-env
                            (map-delta parent-env env)
                            env)
                          ::parent-env
                          ::query-key)
                  query-term]
                 parent-env))
        query-term))))

(defn- gather-style-props [props]
  "Gathers legal DOM style elements in style tag. Can override this behavior by using string key instead of keyword key."
  (let [{root-props false styles true} (group-by (fn [[k v]]
                                                   (some? (dom/style-attributes k)))
                                                 props)]
    (cond-> (into {}
                  root-props)
      (seq styles) (assoc :style
                          (into {}
                                (camel-case-keys styles))))))

(declare refresh)

(defn mount [args]
  "This is used to mount qlkit tied to a dom element (or without a dom element, when used on the server.) The args map can contain :parsers (the map of parsers) :component (The name of the root qlkit component) :state (a state atom) and :remote-handler (function to call for sending out changes to the server). Only one mount can be set up on the client, and one on the server."
  (reset! mount-info args)
  (refresh true))

(defn perform-remote-query [query]
  "This calls the remote handler to process the remote query and offers up a callback that is called when the server has returned the results from the query."
  (when (seq query)
    ((:remote-handler @mount-info)
     query
     (fn [results]
       (doseq [[k v] (map vector query results)]
         (parse-query-term-sync k v {}))
       (refresh false)))))

(defn transact! [& query]
  "This function handles a mutating transaction, originating (usually) from a component context. It first runs the local mutations by parsing the query locally, then sends the remote parts to the server, finally rerenders the entire UI."
  (let [[env component-query]   (if *this*
                                  (let [props (.-props *this*)
                                        env   (aget props "env")
                                        query (aget props "query")]
                                    [env query])
                                  [{} nil])
        query                   (root-query env (normalize-query (concat query component-query)))
        {{spec :spec} :parsers} @mount-info]
    (spec/query-spec (vec query))
    (when spec
      (spec (vec query) :synchronous))
    (parse-query query env)
    (let [q (seq (parse-query-remote query))]
      (spec/query-spec (vec q))
      (when spec
        (spec (vec q) :asynchronous))
      (perform-remote-query q))
    (refresh false)))

#?(:clj (defn- refresh [remote-query?]
          nil)
   :cljs (do 

           (def ^{:doc "Atom containing a function that takes a React component and returns the React component at the root of the application's DOM."}
             make-root-component
             (atom identity))

           (def ^{:doc "Atom containing a map of HTML element keywords (e.g. :div or :table) to React components."}
             component-registry
             (atom {}))

           (defn register-component [k v]
             "Associate an HTML element keyword with a React component."
             (swap! component-registry assoc k v))

           (declare create-element)
           
           (defn- fix-inline-react [this props]
             "These are idiosyncratic properties that may emit raw react components that need to be created."
             (cond-> props
               (contains? props :actions) (update :actions
                                                  (fn [actions]
                                                    (apply array (map (partial create-element this) actions))))
               (contains? props :right-icon) (update :right-icon (partial create-element this))))

           (defn- ensure-element-type [typ]
             (or (cond (keyword? typ) (or (@component-registry typ)
                                          (when (dom/valid-dom-elements typ)
                                            (name typ)))
                       (string? typ)  (when (dom/valid-dom-elements (keyword typ))
                                        typ))
                 (throw (ex-info "Not a valid dom element" {:type typ}))))

           (defn- create-element [this el]
             "This function takes an edn structure describing dom elements and instantiates them with them via React."
             (if (or (string? el) (number? el))
               el
               (let [[typ & more]     el
                     [props children] (if (map? (first more))
                                        [(first more) (rest more)]
                                        [{} more])
                     children         (vec (map (partial create-element this) (splice-in-seqs children)))]
                 (if (and (keyword? typ) (namespace typ))
                   (let [{:keys [::react-class :query] :as class} (@classes typ)]
                     (apply createElement react-class #js {:atts (dissoc props ::env) :env (::env props) :query query} children))
                   (apply createElement
                          (ensure-element-type typ)
                          (->> props
                               gather-style-props
                               (fix-event-references this)
                               fix-classname
                               (fix-inline-react this)
                               camel-case-keys
                               clj->js)
                          children)))))

           (defn- clj-state [state]
             "Pulls state out of the react component state."
             (if state
               (.-state state)
               {}))

           (defn- clj-atts [props]
             "Fetches the component atts out of its react props"
             (if props
               (aget props "atts")
               {}))

           (defn- react-class [class]
             "Creates a react class from the qlkit class description format"
             (js/createReactClass (let [mount (:component-did-mount class)
                                        unmount (:component-will-unmount class)
                                        obj #js {:shouldComponentUpdate (fn [next-props next-state]
                                                                          (this-as this
                                                                            (or (not= (clj-atts (.-props this)) (clj-atts next-props))
                                                                                (not= (clj-state (.-state this)) (clj-state next-state)))))
                                                 :getInitialState       (fn []
                                                                          #js {:state (or (:state class) {})})
                                                 :render                (fn []
                                                                          (this-as this
                                                                            (binding [*this* this]
                                                                              (create-element this ((:render class) (clj-atts (.-props this)) (clj-state (.-state this)))))))}]
                                    (when mount
                                      (set! (.-componentDidMount obj)
                                            (fn []
                                              (this-as this
                                                (binding [*this* this]
                                                  (mount))))))
                                    (when unmount
                                      (set! (.-componentWillUnmount obj)
                                            (fn []
                                              (this-as this
                                                (binding [*this* this]
                                                  (unmount (clj-state (.-state this))))))))
                                    obj)))
           
           (defn update-state! [fun & args]
             "Update the component-local state with the given function"
             (.setState *this*
                        #js {:state (apply fun
                                           (clj-state (.-state *this*))
                                           args)}))

           (defn- refresh [remote-query?]
             "Force a redraw of the entire UI. This will trigger local parsers to gather data, and optionally will fetch data from server as well."
             (let [query (get-query (:component @mount-info))
                   atts       (parse-query-into-map query {})
                   {{spec :spec} :parsers} @mount-info]
               (spec/query-spec (vec query))
               (when spec
                 (spec (vec query) :synchronous))
               (when remote-query?
                 (perform-remote-query (parse-query-remote query)))
               (render (@make-root-component (create-element nil [(:component @mount-info) atts]))
                       (:dom-element @mount-info)))))) 