- You are generating a GitLab Merge Request (MR) for a backend repository.

- Use the provided context from:
	- Source branch: $1
	- Target branch: $2
	- Reviewer : $3 [optional]
	- Ticket number : The ticket key is always embedded in the source branch name (e.g. ONT-232-live → ticket key = ONT-232). If the branch name does not contain a ticket number, omit it.

- You have access to the git diff and git log between these branches. Analyze them to infer:
	- What features or fixes were introduced
	- Which files or components changed
	- The overall purpose of the branch

- Guidlines
	- First check if the [Open state] merge request exists between source and target branch, if there is a open merge request then modify the merge request's title and description, otherwise create a new merge request.
	- Do not include sections like How to Test, Screenshots, or Checklist.
	- Strictly output the description in valid GitLab Markdown.
	- Keep it technical, concise, and professional — written for engineers reviewing backend code.
	- Avoid repeating commit messages verbatim — summarize meaningfully.
	- Maintain a tone suitable for code review, not marketing or release notes.

- Your task
	1. Title - one line, follwoing the generate-git-commit-msg.md format [e.g. "[PgSQL][Shuri] FEAT-9127 Added Default contextual filters support, Added ITs for the same, refactored Analyze ITs."], Keep it concise but meaningful — describe what changed and where.
	2. Description - formatted for GitLab, containing:
		- Summary - Explain what this MR does and why it’s needed. Use context from the Jira ticket if possible.
		- Changes - bullet list of the main modifications (based on diff), Be descriptive and technical — explain how things were changed or improved. Mention major refactors, fixes, or behavioral adjustments.
		- Ticket - "https://ontichelp.atlassian.net/browse/{ticketNumber}"

	3. Open a Merge Request on GitLab
		- After generating the title and description:
		- Source branch: $1
		- Target branch: $2
		- Reviewer : $3
		- Use the title and description you just generated.
		- Check if the merge request exist between source and target branch, if there is open merge request then modify the merge request's title and description, otherwise create a new merge request.
		- Format the md file the gitlab so that it looks good on gitlab.

- Output Schema, return **only** one JSON object (no markdown, no prose). It must
	follow this exact structure and key order:
	{
	  "type" : "Gitlab-MR",
	  "ticket" : link of the ticket,
	  "pr" : link of the gitlab merge request
	  "source" : $1,
	  "target" : $2
	}