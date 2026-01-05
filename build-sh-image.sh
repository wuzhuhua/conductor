#!/bin/bash
#aws ecr get-login-password --region us-west-2  | docker login --username AWS --password-stdin 593045915463.dkr.ecr.us-west-2.amazonaws.com
docker build -t 593045915463.dkr.ecr.us-west-2.amazonaws.com/conductor-server:es8-6.0 -f docker/server/Dockerfile --platform linux/amd64 .
#docker push 593045915463.dkr.ecr.us-west-2.amazonaws.com/conductor-server:es8-6.0

