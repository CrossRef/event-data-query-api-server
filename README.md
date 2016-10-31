# Event Data Query API Server

Experimental service to serve the Event Data Query API from AWS S3, and to cache there.

## TODO

 - warm prev and next pagination
 - canonical links
 - earliest date
 - pre conditions
 - option to exclude sources
 - may need extra error handling in s3 connection

 
## Config keys

    :server-port
    :service-base
    :query-data-bucket
    :s3-access-key-id
    :s3-secret-access-key
