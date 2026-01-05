## Publish Jar Packages to CodeArtifact

### On Local

Not recommended, `cn-engs` use group is only provided read only access to AWS CodeArtifact via the AWS Management Console.

### On AWS CodePipeline

A [CodePipeline](https://us-west-2.console.aws.amazon.com/codesuite/codepipeline/pipelines/conductor-publish-jar/view?region=us-west-2) has been build to automatically build and publish `conductor-annotations`,`conductor-grpc`, `conductor-common `, `conductor-grpc-client` to our [CodeArtifact repo](https://us-west-2.console.aws.amazon.com/codesuite/codeartifact/d/593045915463/rengage/r/conductor?region=us-west-2&packages-meta=eyJmIjp7fSwicyI6e30sIm4iOjIwLCJpIjowfQ) on new commits pushed to `main` branch.
