# set Env Variables
export ULTRON_JIRA_WEBHOOK_URL=""
export JUGGERNAUT_REVIEW_WEBHOOK_URL=""
export GALACTUS_GITLAB_MR_WEBHOOK_URL=""

# jar Files for indexer, notifier
./gradlew codexIndexShadow slackNotifierShadow

# move both jars to the codex folder
mv build/libs/codex-index-generator.jar build/libs/slack-notifier.jar ~/.codex/

# copy session manager script
cp codex_session_manager.sh ~/.codex/

# copy prompts folder
mkdir -p ~/.codex/prompts
cp -r prompts ~/.codex/prompts

brew tap fforster/gitlab-mcp https://gitlab.com/fforster/homebrew-gitlab-mcp.git

#install gitlab mcp
brew install gitlab-mcp

gitlab-mcp auth

mv config.toml ~/.codex/