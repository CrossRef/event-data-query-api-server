(ns event-data-query-api-server.core-test
  (:require [clojure.test :refer :all]
            [event-data-query-api-server.core :as core]))

(deftest event-dois
  (testing "event-dois can extract DOIs from subject or object position, if present in either or both."
    (is (= (core/event-dois {:subj_id "http://example.com/10.5555/not-a-doi" :obj_id "https://en.wikipedia.org/w/Twin_Peaks"})
      #{}))

    (is (= (core/event-dois {:subj_id "http://example.com/10.5555/not-a-doi" :obj_id "http://doi.org/10.5555/12345678"})
           #{"https://doi.org/10.5555/12345678"}))

    (is (= (core/event-dois {:subj_id "https://dx.doi.org/10.5555/242424" :obj_id "http://doi.org/10.5555/12345678"})
           #{"https://doi.org/10.5555/242424" "https://doi.org/10.5555/12345678"}))

    (is (= (core/event-dois {:subj_id "https://dx.doi.org/10.5555/242424" :obj_id "http://example.com/hi"})
           #{"https://doi.org/10.5555/242424"}))))


(deftest event-prefixes
  (testing "event-prefixes can extract DOI prefixes from subject or object position, if DOIs present in either or both."
    (is (= (core/event-prefixes {:subj_id "http://example.com/10.5555/not-a-doi" :obj_id "https://en.wikipedia.org/w/Twin_Peaks"})
      #{})
      "No DOI in subj or obj, no prefixes.")

    (is (= (core/event-prefixes {:subj_id "http://example.com/10.5555/not-a-doi" :obj_id "http://doi.org/10.5555/12345678"})
           #{"10.5555"})
        "DOI in obj only.")

    (is (= (core/event-prefixes {:subj_id "https://dx.doi.org/10.5555/242424" :obj_id "http://doi.org/10.5555/12345678"})
           #{"10.5555"})
        "DOI in subj and obj, but same prefix, means one result.")

    (is (= (core/event-prefixes {:subj_id "https://dx.doi.org/10.4444/242424" :obj_id "http://doi.org/10.5555/12345678"})
           #{"10.4444" "10.5555"})
        "SDOI in subj and obj, with two prefxies, means two results")


    (is (= (core/event-prefixes {:subj_id "https://dx.doi.org/10.5555/242424" :obj_id "http://example.com/hi"})
           #{"10.5555"})))
        "DOI in subj only.")

