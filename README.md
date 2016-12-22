# Event Data Query API Server

Service to serve the Event Data Query API from AWS S3, and to cache there.


## Usage

Provided as a Docker image with Docker Compose file for testing.

To run a demo:

    docker-compose -f docker-compose.yml run -w /usr/src/app -p "8100:8100" test lein run

To run tests

    docker-compose -f docker-compose.yml run -w /usr/src/app test lein test

| Environment variable | Description                         |
|----------------------|-------------------------------------|
| `S3_KEY`             | AWS Key Id                          |
| `S3_SECRET`          | AWS Secret Key                      |
| `S3_BUCKET_NAME`     | AWS S3 bucket name                  |
| `S3_REGION_NAME`     | AWS S3 bucket region name           |
| `PORT`               | Port to listen on                   |
| `STATUS_SERVICE`     | Public URL of the Status service    |
| `JWT_SECRETS`        | Comma-separated list of JTW Secrets |
| `EVENT_BUS_BASE`     | Event Bus URL base                  |
| `SERVICE_BASE`       | Public URL base of this service     |


