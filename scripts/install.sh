#!/usr/bin/env bash
host=frontdoor3

scp -o StrictHostKeyChecking=no -r clarifai.service pi@$host:~/
ssh -t pi@$host -o StrictHostKeyChecking=no "sudo mv ~/clarifai.service /lib/systemd/system"
ssh -t pi@$host -o StrictHostKeyChecking=no "sudo systemctl daemon-reload"
ssh -t pi@$host -o StrictHostKeyChecking=no "sudo systemctl enable clarifai.service"
ssh -t pi@$host -o StrictHostKeyChecking=no "sudo systemctl start clarifai.service"