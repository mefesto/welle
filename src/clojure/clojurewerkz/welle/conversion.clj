(ns clojurewerkz.welle.conversion
  (:require [clojure.data.json :as json])
  (:import [com.basho.riak.client.cap Quora Quorum VClock]
           [com.basho.riak.client.raw StoreMeta FetchMeta DeleteMeta]
           com.basho.riak.client.IRiakObject
           [com.basho.riak.client.builders RiakObjectBuilder BucketPropertiesBuilder]
           [com.basho.riak.client.bucket BucketProperties TunableCAPProps]
           com.basho.riak.client.http.util.Constants
           java.util.Date))

;;
;; Implementation
;;

;; clojure.java.io has these as private, so we had to copy them. MK.
(def ^{:doc "Type object for a Java primitive byte array."}
  byte-array-type (class (make-array Byte/TYPE 0)))


;;
;; API
;;

;; Quorum

(defprotocol QuorumConversion
  (^com.basho.riak.client.cap.Quorum
    to-quorum [input] "Coerces input to a value suitable for representing a read, write or other quorum/quora.
                      Riak Java client supports passing those values as numerical primitives, Quorum and Quora."))


(extend-protocol QuorumConversion
  Integer
  (to-quorum [input]
    (Quorum. ^Integer input))

  Long
  (to-quorum [input]
    (Quorum. ^Long input))

  Quora
  (to-quorum [input]
    (Quorum. ^Quora input))

  Quorum
  (to-quorum [input]
    input))


;; {Store,Fetch,Delete}Meta

(defn to-store-meta
  ""
  (^com.basho.riak.client.raw.StoreMeta
   [r dw pw return-body if-none-match if-not-modified]
   (StoreMeta. (to-quorum r)
               (to-quorum dw)
               (to-quorum pw)
               ^Boolean return-body nil
               ^Boolean if-none-match
               ^Boolean if-not-modified)))

(defn to-fetch-meta
  ""
  (^com.basho.riak.client.raw.FetchMeta
   [r pr not-found-ok basic-quorum head-only return-deleted-vlock if-modified-since if-modified-vclock]
   (FetchMeta. (to-quorum r)
               (to-quorum pr)
               ^Boolean not-found-ok
               ^Boolean basic-quorum
               ^Boolean head-only
               ^Boolean return-deleted-vlock
               ^Date if-modified-since
               ^VClock if-modified-vclock)))

(defn to-delete-meta
  ""
  (^com.basho.riak.client.raw.DeleteMeta
   [r pr w dw pw rw vclock]
   (DeleteMeta. (to-quorum r)
                (to-quorum pr)
                (to-quorum w)
                (to-quorum dw)
                (to-quorum pw)
                (to-quorum rw)
                ^VClock vclock)))


;; Clojure <=> IRiakObject

(defn to-riak-object
  "Builds a Riak object from a map of attributes"
  (^com.basho.riak.client.IRiakObject
   [&{:keys [^String bucket ^String key value content-type metadata indexes vclock vtag last-modified]
      :or {content-type Constants/CTYPE_OCTET_STREAM
           metadata     {}}}]
   (let [^RiakObjectBuilder bldr (doto (RiakObjectBuilder/newBuilder bucket key)
                                   (.withValue        value)
                                   (.withContentType  content-type)
                                   (.withUsermeta     metadata))]
     (when vclock        (.withVClock bldr vclock))
     (when vtag          (.withVtag bldr vtag))
     (when last-modified (.withLastModified bldr last-modified))
     (doseq [[idx-key idx-vals] indexes
             idx-val idx-vals]
       (.addIndex bldr ^String (name idx-key) idx-val))
     (.build bldr))))


;; Serialization

(defprotocol BytesConversion
  (^bytes to-bytes [input] "Converts input to a byte array value that can be stored in a bucket"))

(extend-protocol BytesConversion
  String
  (to-bytes [^String input]
    (.getBytes input)))

(extend byte-array-type
  BytesConversion
  {:to-bytes (fn [^bytes input]
               input) })


(defmulti serialize (fn [_ content-type]
                      content-type))

;; byte streams, strings
(defmethod serialize Constants/CTYPE_OCTET_STREAM
  [value _]
  (to-bytes value))
(defmethod serialize :bytes
  [value _]
  (to-bytes value))
(defmethod serialize Constants/CTYPE_TEXT
  [value _]
  (to-bytes value))
(defmethod serialize Constants/CTYPE_TEXT_UTF8
  [value _]
  (to-bytes value))
(defmethod serialize :text
  [value _]
  (to-bytes value))

;; JSON
(defmethod serialize Constants/CTYPE_JSON
  [value _]
  (json/json-str value))
(defmethod serialize Constants/CTYPE_JSON_UTF8
  [value _]
  (json/json-str value))
(defmethod serialize :json
  [value _]
  (json/json-str value))


(defn ^com.basho.riak.client.bucket.BucketProperties
  to-bucket-properties
  [&{:keys [^Boolean allow-siblings ^Boolean last-write-wins ^Integer n-val ^String backend
            ^Integer big-vclock
            ^Integer small-vclock
            ^Long    old-vclock
            ^Long    young-vclock
            ^Boolean not-found-ok
            ^Boolean basic-quorum
            ^Boolean enable-search
            r w pr dw rw pw]
     :or {allow-siblings  false
          n-val           3
          enable-search   false}}]
  (let [bldr (doto (BucketPropertiesBuilder.)
               (.r             (to-quorum r))
               (.w             (to-quorum w))
               (.pr            (to-quorum pr))
               (.dw            (to-quorum dw))
               (.rw            (to-quorum rw))
               (.pw            (to-quorum pw))
               (.allowSiblings allow-siblings)
               (.search        enable-search)
               (.nVal          n-val)
               (.backend       backend)
               (.smallVClock   small-vclock)
               (.bigVClock     big-vclock)
               (.oldVClock     old-vclock)
               (.youngVClock   young-vclock))]
    (when not-found-ok    (.notFoundOK    bldr not-found-ok))
    (when last-write-wins (.lastWriteWins bldr last-write-wins))
    (when basic-quorum    (.basicQuorum   bldr basic-quorum))
    (.build bldr)))

(defn ^com.basho.riak.client.bucket.TunableCAPProps
  to-tunable-cap-props
  "Build a TunableCAPProps instance from Clojure map"
  [{:keys [r w dw rw pr pw basic-quorum not-found-ok] :or {not-found-ok false}}]
  (TunableCAPProps. (to-quorum r)
                    (to-quorum w)
                    (to-quorum dw)
                    (to-quorum rw)
                    (to-quorum pr)
                    (to-quorum pw)
                    ^Boolean basic-quorum ^Boolean not-found-ok))
