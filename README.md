Computer Vision WebServer for DIY RPI Security Camera

A (SpringBoot) webserver for querying clarifai's vision API to augment the capabilities available with the motion application
Motion calls onMotion.sh which triggers a curl command to hit a servlet.  The servlet queries Clarifai's API,
which returns an analysis of the image taken by the Raspberry Pi camera.  If it meets a particular threshold,
it will potentially notify a callback URL and send an email.

Properties to define:
APIKey: API key to query Clarifai's API with
modelId: the model id to use
threshold: threshhold from 0-1 meaning should notify/email
notificationUrl: URL to notify on success.  ex: http://192.168.0.7:8080/Notification
renameDirectory: Where should we move the files from motion's temp directory
notificationEmail: the email to send the notification to

To launch the server at start, edit your /etc/rc.local 

Make it looks similar to:

_IP=$(hostname -I) || true
if [ "$_IP" ]; then
  printf "My IP address is %s\n" "$_IP"
fi
cd /home/pi
nohup java -jar /home/pi/Clarifai-1.0.jar
exit 0