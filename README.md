# Hugo course gradle plugin

## Configuration

1. Setup credentials file here `~/.aws/credentials`
    ```
    [default]
    aws_access_key_id={YOUR_ACCESS_KEY_ID}
    aws_secret_access_key={YOUR_SECRET_ACCESS_KEY}
    ```
    
1. Set your course name and s3 bucket
    ```groovy
    //...
    hugoCourse {
       name = "event-shunting"
       bucket = "event-shunting-course-staging"
    }
    //...
    ```

## Tasks

Run
```bash
./gradlew tasks
```
to see a list of tasks.
