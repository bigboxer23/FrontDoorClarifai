[![CodeQL](https://github.com/bigboxer23/FrontDoorClarifai/actions/workflows/codeql.yml/badge.svg)](https://github.com/bigboxer23/FrontDoorClarifai/actions/workflows/codeql.yml)

## Raspberry Pi AI security camera

A (SpringBoot) webserver for querying clarifai's vision API to augment the capabilities available with the motion application.
Motion calls onMotion.sh which triggers a curl command to hit a servlet, which queries Clarifai's API,
and returns an analysis of the image taken by the Raspberry Pi's camera.  If it meets a particular threshold,
it will potentially notify a callback URL and send an email.

After Clarifai analyzes the result, the image will either be declared success or a failure. On failure, the image will
be uploaded into a defined S3 bucket into a `Failure/[YYYY-MM]` folder.  On success, the image will also go into a similar
`Success/[YYYY-MM]` folder, but it will also optionally send an email with the resulting image(s) and inform a webhook
url that there's detected motion.  Successful events can be batched so the first event always sends, but subsequent
events can be combined for a multi-image event to prevent spamming.  the `successThreshold` property can define the minimum
time between these events.

### Setting Properties:

Properties can be defined by creating `src/main/java/resources/application.properties` and adding content. An example
file (`application.properties.example`) exists in this directory which can be used as a starting point.<br>

### Available Properties:

ClarifaiPAT: Personal Access Token to query Clarifai's API with (see https://docs.clarifai.com/clarifai-basics/authentication/personal-access-tokens)<br>
ClarifaiAppId: The app's ID<br>
ClarifaiUserId: The user's ID<br>
modelId: the model id to use<br>
threshold: threshhold from 0-1 meaning should notify/email<br>
notificationUrl: URL to notify on success.  ex: http://192.168.0.7:8080/Notification <br>
afterStoredCallback: URL to call when we've stored the file to s3, the URL will include the s3 link appended to the end
ex: http://192.168.0.24:8081/previewContentFromUrl?url=%s <br>
renameDirectory: Where should we move the files from motion's temp directory<br>
notificationEmail: the email to send the notification to<br>
server.port: Port number for server to run on<br>
successThreshold: Number of minutes until another success notification and/or email is sent.  This prevents spamming
if there are a large number of clustered events.  At the end of this period if there are unsent successful events,
they will be batched together in one email/notification<br>
notificationEmail: Email address to send emails to on success<br>
sendingEmailAccount: Email username to send emails from<br>
sendingEmailPassword: Password of email account to send from<br>
s3BucketName: Name of S3 bucket to upload success or failure images to<br>
s3Region: Region of bucket<br>
logbackserver: Optional IP/port for a logback server to get events. If not defined, modify `logback.xml` to log to
stdout or a file. Example: 192.168.0.7:5671<br>
basepath: path prefix to append to calls to /analyze.  Example: /var/lib/motion/

### Installation

Edit and run the `scripts/install.sh`.  It will create a service definition which will launch the app automatically