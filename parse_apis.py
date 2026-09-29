import urllib.request
import re
import json

url = 'https://raw.githubusercontent.com/public-apis/public-apis/master/README.md'
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
with urllib.request.urlopen(req, timeout=20) as response:
    content = response.read().decode('utf-8')

categories = {}
current_cat = None
link_pattern = re.compile(r'\[(.*?)\]\((.*?)\)')

for line in content.splitlines():
    line_stripped = line.strip()
    if line_stripped.startswith('### ') and 'APIs Covered Under' not in line_stripped:
        cat_name = line_stripped.replace('###', '').strip()
        parts = [p.strip() for p in cat_name.split('###') if p.strip()]
        if parts:
            current_cat = parts[0]
            if current_cat not in categories:
                categories[current_cat] = []
    elif line_stripped.startswith('|') and current_cat:
        if ':---' in line_stripped or 'API | Description' in line_stripped:
            continue
        cols = [c.strip() for c in line_stripped.split('|')[1:-1]]
        if len(cols) >= 5:
            api_col = cols[0]
            desc = cols[1]
            auth = cols[2].replace('`', '')
            https = cols[3]
            cors = cols[4]
            
            m = link_pattern.search(api_col)
            if m:
                name = m.group(1).strip()
                link = m.group(2).strip()
                categories[current_cat].append({
                    'name': name,
                    'link': link,
                    'description': desc,
                    'auth': auth,
                    'https': https,
                    'cors': cors,
                    'category': current_cat
                })

total_apis = sum(len(apis) for apis in categories.values())
print(f"Categories found: {len(categories)}")
print(f"Total APIs parsed: {total_apis}")
for cat, apis in categories.items():
    print(f"  {cat}: {len(apis)} APIs")

with open("all_public_apis.json", "w", encoding="utf-8") as f:
    json.dump(categories, f, indent=2, ensure_ascii=False)
