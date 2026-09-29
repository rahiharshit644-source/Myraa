import json

with open("all_public_apis.json", "r", encoding="utf-8") as f:
    categories = json.load(f)

total_apis = sum(len(apis) for apis in categories.values())

lines = []
lines.append("# Complete Directory of Public APIs Integrated into MYRA")
lines.append("")
lines.append(f"**Source Repository**: [public-apis/public-apis](https://github.com/public-apis/public-apis)")
lines.append(f"**Total Categories**: {len(categories)}")
lines.append(f"**Total Integrated APIs**: {total_apis}")
lines.append("")
lines.append("This directory contains the exhaustive catalog of all public APIs integrated into MYRA.")
lines.append("MYRA uses this catalog along with semantic reasoning to automatically select, recommend,")
lines.append("and invoke the most appropriate API for any user request or background task.")
lines.append("")
lines.append("---")
lines.append("")
lines.append("## Table of Categories")
lines.append("")
for i, (cat, apis) in enumerate(sorted(categories.items()), 1):
    slug = cat.lower().replace(" & ", "-").replace(" ", "-").replace("/", "-")
    lines.append(f"{i}. [{cat}](#{slug}) ({len(apis)} APIs)")
lines.append("")
lines.append("---")
lines.append("")

for cat, apis in sorted(categories.items()):
    slug = cat.lower().replace(" & ", "-").replace(" ", "-").replace("/", "-")
    lines.append(f"## {cat}")
    lines.append(f"**Total APIs**: {len(apis)}")
    lines.append("")
    lines.append("| API Name | Description | Auth | HTTPS | CORS | Website / Docs |")
    lines.append("| :--- | :--- | :--- | :--- | :--- | :--- |")
    for api in apis:
        name = api['name'].replace('|', '\|')
        desc = api['description'].replace('|', '\|')
        auth = api['auth'] if api['auth'] else 'No'
        https = api['https']
        cors = api['cors']
        link = api['link']
        lines.append(f"| **{name}** | {desc} | `{auth}` | {https} | {cors} | [{link}]({link}) |")
    lines.append("")

with open("PUBLIC_APIS_DIRECTORY.md", "w", encoding="utf-8") as f:
    f.write("\n".join(lines))

print(f"Generated PUBLIC_APIS_DIRECTORY.md with {len(lines)} lines.")
