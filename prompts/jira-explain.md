
# Jira Context Summary Prompt

Given a Jira ticket number $1, gather and summarize all relevant context to help a developer — particularly a backend developer — quickly understand the background, purpose, and next steps for the ticket.

Add Prefix [JIRA-EXPLAIN-$1] to the output response [dont add extra info in this line]
--- 

## Instructions

### 1. Identify Hierarchy
- Check if the ticket has a **parent**. If so, include the parent’s **summary**, **description**, and the overall **goal or intent** of that parent ticket.  
- If the ticket has **subtasks**, include a concise summary of each subtask and its **status**.

---

### 2. Review Relationships
- Identify any **linked tickets** (e.g., *relates to*, *blocks*, *is blocked by*, etc.) and briefly explain how each one is connected or relevant.

---

### 3. Include Linked Resources
- Gather information from any **linked resources** such as Confluence pages, Slack threads, or external documentation mentioned within the ticket.  
- Summarize only the **most relevant details** that add context or explain **decisions, blockers, or background information**.

---

### 4. Deliverable
Present a **clear, well-structured overview** that includes:
- Ticket hierarchy and relationships  
- Context from linked resources  
- Purpose and background summary  
- Current state and logical next steps  

---

### 5. Links and Attachments
- Gather all **relevant external links and attachments** associated with the ticket, **excluding Jira issue URLs** themselves (e.g., main ticket, parent, subtasks, or related issue links).  
- Include links found **anywhere** — in the parent, subtasks, comments, or description fields — such as:
  - Confluence documents  
  - Slack discussions  
  - Figma designs  
  - Videos, screenshots, documents, or other attachments  
- Present them in a **structured list** with brief context describing each link or attachment (e.g., “Design prototype,” “Team discussion,” “Meeting notes,” etc.).
- Use HyperLink for links
- if link is there in ticket then you have to include it here don't just mention the file name or attachment name.
---