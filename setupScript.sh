# set Env Variables
export ULTRON_JIRA_WEBHOOK_URL="https://hooks.slack.com/triggers/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"
export JUGGERNAUT_REVIEW_WEBHOOK_URL="https://hooks.slack.com/triggers/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"
export GALACTUS_GITLAB_MR_WEBHOOK_URL="https://hooks.slack.com/triggers/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"

# jar Files for indexer, notifier
./gradlew codexIndexShadow slackNotifierShadow

# move both jars to the codex folder
mv build/libs/codex-index-generator.jar build/libs/slack-notifier.jar ~/.codex/

# copy session manager script
cp codex_session_manager.sh ~/.codex/

# copy prompts folder
mkdir -p ~/.codex/prompts
cp -r prompts ~/.codex/prompts

#install gitlab mcp
brew install gitlab-mcp

mv config.toml ~/.codex/