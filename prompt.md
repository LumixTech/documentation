You are working inside a Docusaurus documentation project.

I already created a `guide.md` file that defines the writing style, structure, grouping logic, and documentation expectations. You must strictly follow `guide.md` while generating files and content.

Your task is to generate a documentation structure and create the related Markdown/MDX pages for the topics below.

## Main Goal

Create a clean, professional, well-grouped Docusaurus documentation set based on my technical study notes.

The output must:

- follow the structure and conventions defined in `guide.md`
- generate meaningful folders/categories
- create page files with proper titles and frontmatter
- group related topics under the correct sections
- use consistent terminology
- explain concepts with an engineering and product mindset
- include both sector-standard explanations and our team/project notes
- include glossary-like sections inside each topic
- include introduction, development, and conclusion sections
- include research keywords for further learning
- include pseudocode-style explanations where useful
- emphasize why the topic matters in real product development
- explain real-world problems, trade-offs, and solution decisions

## Writing Rules

For every generated document:

1. Use a professional but practical engineering tone.
2. Write as if this documentation will be used for:
   - internal learning
   - onboarding
   - architecture understanding
   - future blog/article adaptation
3. Each page should contain these sections unless `guide.md` defines a better variant:
   - Introduction
   - Why It Matters
   - Core Concepts
   - Problem in Real Product Development
   - Approach / Design
   - Sector Standard / Best Practice
   - Pseudocode / Decision Flow / Lifecycle / Policy Example
   - Our Notes / Team Decisions
   - Glossary
   - Research Keywords
   - Conclusion
4. Do not write shallow content.
5. Do not just define terms; explain relationships between them.
6. Highlight trade-offs and design decisions.
7. Where relevant, compare alternatives.
8. Keep terminology consistent across all files.
9. If a topic references another topic, reflect that naturally in the structure and internal linking.
10. If useful, add bullet points, tables, or decision lists, but prefer clarity over verbosity.
11. If a topic includes database or authorization logic, use pseudocode and structured examples.
12. If a topic includes architecture decisions, explain both the problem and why the chosen solution is reasonable.
13. Add a small glossary section inside every page for technical terms used in that page.
14. Do not invent product-specific facts beyond the notes I provide. If needed, frame project-specific points as “our notes”, “our preferred approach”, or “our current design decision”.

## Important Expectations

- Correctly organize the pages into meaningful documentation groups.
- Choose folder names and sidebar grouping that fit Docusaurus best practices.
- Prefer stable and scalable structure over flat file dumping.
- Create category groupings that make sense for long-term maintenance.
- If needed, create an additional shared glossary page.
- If needed, create an engineering decisions or architecture notes section.
- Use kebab-case file names.
- Use readable, descriptive page titles.
- Add frontmatter to each page.
- If generating `_category_.json` or category metadata files is appropriate, do that too.
- Make the final structure feel like a real documentation portal, not a random set of notes.

## Topic Set

Generate documentation pages for these topics:

1. Spring Security Authentication Flow with JWT + Refresh Token
   Notes:
   - Auth flow is the main security gate.
   - Access/refresh separation is critical.
   - Login, refresh, logout endpoint flow should be clearly explained.
   - Hybrid usage:
     - Access token: stateless
     - Refresh token: stateful
     - Session/device: stateful

2. Session, Device, and Refresh Token Lifecycle
   Notes:
   - JWT alone is not enough.
   - Device-based sessions, logout-all, revoke token design are required.
   - USER_SESSIONS and REFRESH_TOKENS lifecycle should be documented.

3. Hybrid Authorization Model: RBAC + ABAC
   Notes:
   - This will be a hybrid model.
   - user_permission + role_permission + common permissions + allow/deny
   - We need role -> permission -> endpoint/use-case style thinking.
   - Explain why RBAC alone is insufficient in real products.

4. KVKK / GDPR Fundamentals: Legal Basis vs Consent
   Notes:
   - Not every data processing activity depends on explicit consent.
   - Wrong modeling breaks product flows.
   - A processing-purpose and legal-basis matrix should be reflected.

5. Audit Log Design
   Notes:
   - Must answer: who did what, when?
   - Critical especially for payment, health, counseling/PDR, permission changes.
   - AUDIT_LOGS table and critical action list should be reflected.

6. Retention, Anonymization, and DSAR Flow
   Notes:
   - One of the hardest compliance areas.
   - DSAR request -> approval -> anonymization -> audit flow should be explained.

7. PostgreSQL Composite Index Ordering
   Notes:
   - Explain why order matters.
   - Use the B-tree / phone book analogy.
   - Example:
     INDEX (tenant_id, user_id)
   - DB first narrows by tenant_id, then by user_id.
   - In our thinking, filtering will start with tenant_id.

8. PostgreSQL Extensions / Plugins Evaluation
   Notes:
   - PostgreSQL plugins/extensions should be reviewed and only needed ones should be added.
   - Mention that extension decisions should be made by actual need and operational fit.
   - Example note: investigate needed plugins such as pg\_\* lifecycle-related tools if relevant.

9. Frontend URL Path Strategy for Visible/Invisible IDs
   Notes:
   - Whether IDs appear in URL paths should be controlled by system parameters.
   - This should be managed from Redux/root reducer/base state.
   - In-page routes and navigations should behave based on that state.
   - A SmartNavigation component may exist.

10. Tenant-based Row-Level Security (RLS)
    Notes:

- RLS policies will be written tenant_id based.
- Explain tenant isolation from DB perspective.

11. Read / Write Replica Rules
    Notes:

- We need rule-based thinking for read/write replica usage.

12. Flyway Migrations and Zero-Downtime DB Changes
    Notes:

- Production database changes must be rollout-safe.
- Use backward-compatible schema changes and expand/contract pattern.
- Include a two-stage column rename and table split migration scenario.

13. Product Development Problems and Solution Decisions
    Notes:

- We want blog-style / engineering-note-style writing about:
  “What problems we encountered while building the product, and what approach/solution we found.”
- This section should feel reusable for future engineering blog posts.

## Standardized Terminology

Use these terms consistently where relevant:

- access token
- refresh token
- session
- device session
- role_permission
- user_permission
- common_permission
- allow
- deny
- tenant_id
- RLS policy
- audit log
- retention policy
- anonymization
- DSAR workflow
- smart navigation
- read replica
- write primary
- Flyway migration
- zero-downtime migration
- backward-compatible schema change
- expand and contract

## Preferred Structure Hint

Unless `guide.md` defines a better structure, prefer a structure like:

- security-compliance/
- database-architecture/
- frontend-architecture/
- engineering-notes/
- glossary/

But improve this if `guide.md` suggests something better.

## Output Mode

You should:

1. inspect and obey `guide.md`
2. propose or directly create the folder/file structure
3. generate the pages with content
4. ensure correct grouping/navigation
5. keep the structure maintainable and expandable

If there is any ambiguity, choose the most professional, scalable documentation structure.

Do not ask for confirmation.
Act like a documentation architect and content writer.
Generate the documentation in a way that is production-quality for a Docusaurus project.
