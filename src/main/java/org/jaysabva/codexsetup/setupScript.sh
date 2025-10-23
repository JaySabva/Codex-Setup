# Set Env Variables
export ULTRON_JIRA_WEBHOOK_URL = "https://hooks.slack.com/triggers/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"
export JUGGERNAUT_REVIEW_WEBHOOK_URL = "https://hooks.slack.com/triggers/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"
export GALACTUS_GITLAB_MR_WEBHOOK_URL = "https://hooks.slack.com/triggers/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"

# Jar Files for indexer, notifier
./gradlew codexIndexShadow slackNotifierShadow

# Move both jars to the codex folder
mv build/libs/codex-index-generator-0.0.1-SNAPSHOT.jar build/libs/slack-notifier-0.0.1-SNAPSHOT.jar ~/.codex/


