(ns com.rpl.specter.navs
  #?(:cljs (:require-macros
            [com.rpl.specter
              :refer
              [defnav defrichnav]]
            [com.rpl.specter.util-macros :refer
              [doseqres]]))
  (:use #?(:clj [com.rpl.specter.macros :only [defnav defrichnav]])
        #?(:clj [com.rpl.specter.util-macros :only [doseqres]]))
  (:require [com.rpl.specter.impl :as i]
            [clojure.walk :as walk]
            #?(:clj [clojure.core.reducers :as r])))


(defn not-selected?*
  [compiled-path vals structure]
  (->> structure
       (i/compiled-select-any* compiled-path vals)
       (identical? i/NONE)))

(defn selected?*
  [compiled-path vals structure]
  (not (not-selected?* compiled-path vals structure)))

(defn walk-select [pred continue-fn structure]
  (let [ret (i/mutable-cell i/NONE)
        walker (fn this [structure]
                 (if (pred structure)
                   (let [r (continue-fn structure)]
                     (if-not (identical? r i/NONE)
                       (i/set-cell! ret r))
                     r)

                   (walk/walk this identity structure)))]

    (walker structure)
    (i/get-cell ret)))


(defn key-select [akey structure next-fn]
  (next-fn (get structure akey)))

(defn key-transform [akey structure next-fn]
  (assoc structure akey (next-fn (get structure akey))))


(defn all-select [structure next-fn]
  (doseqres i/NONE [e structure]
    (next-fn e)))

#?(
   :clj
   (defn queue? [coll]
     (instance? clojure.lang.PersistentQueue coll))

   :cljs
   (defn queue? [coll]
     (= (type coll) (type #queue []))))


(defprotocol AllTransformProtocol
  (all-transform [structure next-fn]))

(defn- non-transient-map-all-transform [structure next-fn empty-map]
  (reduce-kv
    (fn [m k v]
      (let [newkv (next-fn [k v])]
        (if (identical? newkv i/NONE)
          m
          (assoc m (nth newkv 0) (nth newkv 1)))))

    empty-map
    structure))

(defn not-NONE? [v]
  (-> v (identical? i/NONE) not))


(defn- all-transform-list [structure next-fn]
  ;; this is done to maintain order, otherwise lists get reversed
  (->> structure
       (into '()
             (comp (map next-fn) (filter not-NONE?)))
       reverse
       ))

(extend-protocol AllTransformProtocol
  nil
  (all-transform [structure next-fn]
    nil)


  ;; in cljs they're PersistentVector so don't need a special case
  #?(:clj clojure.lang.MapEntry)
  #?(:clj
     (all-transform [structure next-fn]
       (let [newk (next-fn (key structure))
             newv (next-fn (val structure))]
         (clojure.lang.MapEntry. newk newv))))


  #?(:clj clojure.lang.IPersistentVector :cljs cljs.core/PersistentVector)
  (all-transform [structure next-fn]
    (into []
      (comp (map next-fn)
            (filter not-NONE?))
      structure))

  #?(:clj clojure.lang.PersistentHashSet :cljs cljs.core/PersistentHashSet)
  (all-transform [structure next-fn]
    (into #{}
      (comp (map next-fn)
            (filter not-NONE?))
      structure))

  #?(:clj clojure.lang.PersistentArrayMap)
  #?(:clj
     (all-transform [structure next-fn]
       (let [k-it (.keyIterator structure)
             v-it (.valIterator structure)
             none-cell (i/mutable-cell 0)
             len (.count structure)
             array (i/fast-object-array (* 2 len))]
         (loop [i 0
                j 0]
           (if (.hasNext k-it)
             (let [k (.next k-it)
                   v (.next v-it)
                   newkv (next-fn [k v])]
               (if (identical? newkv i/NONE)
                (do
                  (i/update-cell! none-cell inc)
                  (recur (+ i 2) j))
                (do
                  (aset array j (nth newkv 0))
                  (aset array (inc j) (nth newkv 1))
                  (recur (+ i 2) (+ j 2)))))))
         (let [none-count (i/get-cell none-cell)
               array (if (not= 0 none-count)
                       (java.util.Arrays/copyOf array (int (* 2 (- len none-count))))
                       array
                       )]
          (clojure.lang.PersistentArrayMap/createAsIfByAssoc array)))))


  #?(:cljs cljs.core/PersistentArrayMap)
  #?(:cljs
     (all-transform [structure next-fn]
       (non-transient-map-all-transform structure next-fn {})))


  #?(:clj clojure.lang.PersistentTreeMap :cljs cljs.core/PersistentTreeMap)
  (all-transform [structure next-fn]
    (non-transient-map-all-transform structure next-fn (empty structure)))


  #?(:clj clojure.lang.PersistentHashMap :cljs cljs.core/PersistentHashMap)
  (all-transform [structure next-fn]
    (persistent!
      (reduce-kv
        (fn [m k v]
          (let [newkv (next-fn [k v])]
            (if (identical? newkv i/NONE)
              m
              (assoc! m (nth newkv 0) (nth newkv 1)))))

        (transient
          #?(:clj clojure.lang.PersistentHashMap/EMPTY :cljs cljs.core.PersistentHashMap.EMPTY))

        structure)))



  #?(:clj Object)
  #?(:clj
     (all-transform [structure next-fn]
       (let [empty-structure (empty structure)]
         (cond (and (list? empty-structure) (not (queue? empty-structure)))
               (all-transform-list structure next-fn)

               (map? structure)
               ;; reduce-kv is much faster than doing r/map through call to (into ...)
               (reduce-kv
                 (fn [m k v]
                   (let [newkv (next-fn [k v])]
                     (if (identical? newkv i/NONE)
                      m
                      (assoc m (nth newkv 0) (nth newkv 1)))))

                 empty-structure
                 structure)


               :else
               (->> structure
                    (r/map next-fn)
                    (r/filter not-NONE?)
                    (into empty-structure))))))


  #?(:cljs default)
  #?(:cljs
     (all-transform [structure next-fn]
       (let [empty-structure (empty structure)]
         (if (and (list? empty-structure) (not (queue? empty-structure)))
           (all-transform-list structure next-fn)
           (into empty-structure
                 (comp (map next-fn) (filter not-NONE?))
                 structure))))))



(defprotocol MapTransformProtocol
  (map-vals-transform [structure next-fn])
  (map-keys-transform [structure next-fn])
  )



(defn map-vals-non-transient-transform [structure empty-map next-fn]
  (reduce-kv
    (fn [m k v]
      (let [newv (next-fn v)]
        (if (identical? newv i/NONE)
          m
          (assoc m k newv))))
    empty-map
    structure))

(defn map-keys-non-transient-transform [structure empty-map next-fn]
  (reduce-kv
    (fn [m k v]
      (let [newk (next-fn k)]
        (if (identical? newk i/NONE)
          m
          (assoc m newk v))))
    empty-map
    structure))

(extend-protocol MapTransformProtocol
  nil
  (map-vals-transform [structure next-fn]
    nil)
  (map-keys-transform [structure next-fn]
    nil)


  #?(:clj clojure.lang.PersistentArrayMap)
  #?(:clj
     (map-vals-transform [structure next-fn]
       (let [k-it (.keyIterator structure)
             v-it (.valIterator structure)
             none-cell (i/mutable-cell 0)
             len (.count structure)
             array (i/fast-object-array (* 2 len))]
         (loop [i 0
                j 0]
           (if (.hasNext k-it)
             (let [k (.next k-it)
                   v (.next v-it)
                   newv (next-fn v)]
               (if (identical? newv i/NONE)
                (do
                  (i/update-cell! none-cell inc)
                  (recur (+ i 2) j))
                (do
                  (aset array j k)
                  (aset array (inc j) newv)
                  (recur (+ i 2) (+ j 2)))))))
         (let [none-count (i/get-cell none-cell)
               array (if (not= 0 none-count)
                        (java.util.Arrays/copyOf array (int (* 2 (- len none-count))))
                        array
                        )]
          (clojure.lang.PersistentArrayMap. array)))))
  #?(:clj
     (map-keys-transform [structure next-fn]
       (let [k-it (.keyIterator structure)
             v-it (.valIterator structure)
             none-cell (i/mutable-cell 0)
             len (.count structure)
             array (i/fast-object-array (* 2 len))]
         (loop [i 0
                j 0]
           (if (.hasNext k-it)
             (let [k (.next k-it)
                   v (.next v-it)
                   newk (next-fn k)]
               (if (identical? newk i/NONE)
                (do
                  (i/update-cell! none-cell inc)
                  (recur (+ i 2) j))
                (do
                  (aset array j newk)
                  (aset array (inc j) v)
                  (recur (+ i 2) (+ j 2)))))))
         (let [none-count (i/get-cell none-cell)
               array (if (not= 0 none-count)
                        (java.util.Arrays/copyOf array (int (* 2 (- len none-count))))
                        array
                        )]
          (clojure.lang.PersistentArrayMap/createAsIfByAssoc array)))))

  #?(:cljs cljs.core/PersistentArrayMap)
  #?(:cljs
     (map-vals-transform [structure next-fn]
       (map-vals-non-transient-transform structure {} next-fn)))
  #?(:cljs
     (map-keys-transform [structure next-fn]
       (map-keys-non-transient-transform structure {} next-fn)))


  #?(:clj clojure.lang.PersistentTreeMap :cljs cljs.core/PersistentTreeMap)
  (map-vals-transform [structure next-fn]
    (map-vals-non-transient-transform structure (empty structure) next-fn))
  (map-keys-transform [structure next-fn]
    (map-keys-non-transient-transform structure (empty structure) next-fn))


  #?(:clj clojure.lang.PersistentHashMap :cljs cljs.core/PersistentHashMap)
  (map-vals-transform [structure next-fn]
    (persistent!
      (reduce-kv
        (fn [m k v]
          (let [newv (next-fn v)]
            (if (identical? newv i/NONE)
              m
              (assoc! m k newv))))
        (transient
          #?(:clj clojure.lang.PersistentHashMap/EMPTY :cljs cljs.core.PersistentHashMap.EMPTY))

        structure)))
  (map-keys-transform [structure next-fn]
    (persistent!
      (reduce-kv
        (fn [m k v]
          (let [newk (next-fn k)]
            (if (identical? newk i/NONE)
              m
              (assoc! m newk v))))
        (transient
          #?(:clj clojure.lang.PersistentHashMap/EMPTY :cljs cljs.core.PersistentHashMap.EMPTY))

        structure)))

  #?(:clj Object :cljs default)
  (map-vals-transform [structure next-fn]
    (reduce-kv
      (fn [m k v]
        (let [newv (next-fn v)]
          (if (identical? newv i/NONE)
            m
            (assoc m k newv))))
      (empty structure)
      structure))
  (map-keys-transform [structure next-fn]
    (reduce-kv
      (fn [m k v]
        (let [newk (next-fn k)]
          (if (identical? newk i/NONE)
            m
            (assoc m newk v))))
      (empty structure)
      structure)))

(defn srange-select [structure start end next-fn]
  (next-fn
    (if (string? structure)
      (subs structure start end)
      (-> structure vec (subvec start end))
      )))

(def srange-transform i/srange-transform*)


(defn extract-basic-filter-fn [path]
  (cond (fn? path)
        path

        (and (coll? path)
             (every? fn? path))
        (reduce
          (fn [combined afn]
            (fn [structure]
              (and (combined structure) (afn structure))))

          path)))




(defn if-select [vals structure next-fn then-tester then-nav else-nav]
  (i/exec-select*
    (if (then-tester structure) then-nav else-nav)
    vals
    structure
    next-fn))



(defn if-transform [vals structure next-fn then-tester then-nav else-nav]
  (i/exec-transform*
    (if (then-tester structure) then-nav else-nav)
    vals
    structure
    next-fn))




(defprotocol AddExtremes
  (append-all [structure elements])
  (prepend-all [structure elements])
  (append-one [structure elem])
  (prepend-one [structure elem])
  )

(extend-protocol AddExtremes
  nil
  (append-all [_ elements]
    elements)
  (prepend-all [_ elements]
    elements)
  (append-one [_ elem]
    (list elem))
  (prepend-one [_ elem]
    (list elem))

  #?(:clj clojure.lang.IPersistentVector :cljs cljs.core/PersistentVector)
  (append-all [structure elements]
    (reduce conj structure elements))
  (prepend-all [structure elements]
    (let [ret (transient [])]
      (as-> ret <>
            (reduce conj! <> elements)
            (reduce conj! <> structure)
            (persistent! <>))))
  (append-one [structure elem]
    (conj structure elem))
  (prepend-one [structure elem]
    (into [elem] structure))


  #?(:clj Object :cljs default)
  (append-all [structure elements]
    (concat structure elements))
  (prepend-all [structure elements]
    (concat elements structure))
  (append-one [structure elem]
    (concat structure [elem]))
  (prepend-one [structure elem]
    (cons elem structure))
  )



(defprotocol UpdateExtremes
  (update-first [s afn])
  (update-last [s afn]))

(defprotocol GetExtremes
  (get-first [s])
  (get-last [s]))

(defprotocol FastEmpty
  (fast-empty? [s]))

(defnav PosNavigator [getter updater]
  (select* [this structure next-fn]
    (if-not (fast-empty? structure)
      (next-fn (getter structure))
      i/NONE))
  (transform* [this structure next-fn]
    (if (fast-empty? structure)
      structure
      (updater structure next-fn))))

(defn- update-first-list [l afn]
  (let [newf (afn (first l))
        restl (rest l)]
    (if (identical? i/NONE newf)
      restl
      (cons newf restl))))

(defn- update-last-list [l afn]
  (let [lastl (afn (last l))
        bl (butlast l)]
    (if (identical? i/NONE lastl)
      (if (nil? bl) '() bl)
      (concat bl [lastl]))))

#?(
   :clj
   (defn vec-count [^clojure.lang.IPersistentVector v]
     (.length v))

   :cljs
   (defn vec-count [v]
     (count v)))


#?(
   :clj
   (defn transient-vec-count [^clojure.lang.ITransientVector v]
     (.count v))

   :cljs
   (defn transient-vec-count [v]
     (count v)))


(extend-protocol UpdateExtremes
  #?(:clj clojure.lang.IPersistentVector :cljs cljs.core/PersistentVector)
  (update-first [v afn]
    (let [val (nth v 0)
          newv (afn val)]
      (if (identical? i/NONE newv)
        (subvec v 1)
        (assoc v 0 newv)
        )))

  (update-last [v afn]
    ;; type-hinting vec-count to ^int caused weird errors with case
    (let [c (int (vec-count v))]
      (case c
        1 (let [[e] v
                newe (afn e)]
                (if (identical? i/NONE newe)
                  []
                  [newe]))
        2 (let [[e1 e2] v
                 newe (afn e2)]
            (if (identical? i/NONE newe)
              [e1]
              [e1 newe]))
        (let [i (dec c)
              newe (afn (nth v i))]
          (if (identical? i/NONE newe)
            (pop v)
            (assoc v i newe))))))

  #?(:clj String :cljs string)
  (update-first [s afn]
    (let [rests (subs s 1 (count s))
          newb (afn (nth s 0))]
      (if (identical? i/NONE newb)
        rests
        (str newb rests))))

  (update-last [s afn]
    (let [last-idx (-> s count dec)
          newl (afn (nth s last-idx))
          begins (subs s 0 last-idx)]
      (if (identical? i/NONE newl)
        begins
        (str begins newl)
        )))

  #?(:clj Object :cljs default)
  (update-first [l val]
    (update-first-list l val))
  (update-last [l val]
    (update-last-list l val)))


(extend-protocol GetExtremes
  #?(:clj clojure.lang.IPersistentVector :cljs cljs.core/PersistentVector)
  (get-first [v]
    (nth v 0))
  (get-last [v]
    (peek v))

  #?(:clj Object :cljs default)
  (get-first [s]
    (first s))
  (get-last [s]
    (last s))

  #?(:clj String :cljs string)
  (get-first [s]
    (nth s 0))
  (get-last [s]
    (nth s (-> s count dec))
    ))



(extend-protocol FastEmpty
  nil
  (fast-empty? [_] true)

  #?(:clj clojure.lang.IPersistentVector :cljs cljs.core/PersistentVector)
  (fast-empty? [v]
    (= 0 (vec-count v)))
  #?(:clj clojure.lang.ITransientVector :cljs cljs.core/TransientVector)
  (fast-empty? [v]
    (= 0 (transient-vec-count v)))
  #?(:clj Object :cljs default)
  (fast-empty? [s]
    (empty? s)))


(defn walk-until [pred on-match-fn structure]
  (if (pred structure)
    (on-match-fn structure)
    (walk/walk (partial walk-until pred on-match-fn) identity structure)))


(defn- do-keypath-transform [vals structure key next-fn]
  (let [newv (next-fn vals (get structure key))]
    (if (identical? newv i/NONE)
      (if (sequential? structure)
        (i/srange-transform* structure key (inc key) (fn [_] []))
        (dissoc structure key))
      (assoc structure key newv))))

(defrichnav
  ^{:doc "Navigates to the specified key, navigating to nil if it does not exist.
          Setting the value to NONE will remove it from the collection."}
  keypath*
  [key]
  (select* [this vals structure next-fn]
    (next-fn vals (get structure key)))
  (transform* [this vals structure next-fn]
    (do-keypath-transform vals structure key next-fn)
    ))


(defrichnav
  ^{:doc "Navigates to the key only if it exists in the map. Setting the value to NONE
          will remove it from the collection."}
  must*
  [k]
  (select* [this vals structure next-fn]
    (if (contains? structure k)
      (next-fn vals (get structure k))
      i/NONE))
  (transform* [this vals structure next-fn]
   (if (contains? structure k)
     (do-keypath-transform vals structure k next-fn)
     structure)))

(defrichnav nthpath*
  ^{:doc "Navigates to the given position in the sequence. Setting the value to NONE
          will remove it from the sequence. Works for all sequence types."}
  [i]
  (select* [this vals structure next-fn]
    (next-fn vals (nth structure i)))
  (transform* [this vals structure next-fn]
    (if (vector? structure)
      (let [newv (next-fn vals (nth structure i))]
        (if (identical? newv i/NONE)
          (i/srange-transform* structure i (inc i) (fn [_] []))
            (assoc structure i newv)))
      (i/srange-transform* ; can make this much more efficient with alternate impl
        structure
        i
        (inc i)
        (fn [[e]]
          (let [v (next-fn vals e)]
           (if (identical? v i/NONE)
             []
             [v])
           ))))))
