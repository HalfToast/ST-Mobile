# Read-only stats probe: stpanel character_stats_browser logic (lines 274-351), user dir from ST_USER.
# Prints name\tmessages\tlast_active_epoch for all characters, newest first.
import os, json, glob, datetime, re

st_dir = "/data/data/com.termux/files/home/SillyTavern"
if not os.path.isdir(st_dir):
    st_dir = os.path.join(os.path.expanduser("~"), "SillyTavern")
user_dir = os.path.join(st_dir, "data", os.environ.get("ST_USER", "default-user"))
chars_dir = os.path.join(user_dir, "characters")
chats_dir = os.path.join(user_dir, "chats")
backups_dir = os.path.join(user_dir, "backups")
stats_file = os.path.join(user_dir, "stats.json")

stats = {}
if os.path.exists(stats_file):
    try:
        with open(stats_file) as f:
            raw = json.load(f)
            for k, v in raw.items():
                if isinstance(v, dict):
                    clean = k.replace(".png", "").replace("default_", "").lower()
                    stats[clean] = {
                        "messages": v.get("user_msg_count", 0) + v.get("non_user_msg_count", 0),
                        "words": v.get("user_word_count", 0) + v.get("non_user_word_count", 0),
                        "last_active": v.get("date_last_chat", 0) / 1000 if v.get("date_last_chat") else 0
                    }
    except:
        pass

all_chars = {}
if os.path.exists(chars_dir):
    for f in os.listdir(chars_dir):
        if f.endswith((".png", ".json")):
            name = os.path.splitext(f)[0]
            k = name.lower()
            all_chars[k] = {
                "name": name,
                "messages": stats.get(k, {}).get("messages", 0),
                "words": stats.get(k, {}).get("words", 0),
                "last_active": stats.get(k, {}).get("last_active", 0),
                "latest_file": None
            }

if os.path.exists(backups_dir):
    for bf in os.listdir(backups_dir):
        if bf.startswith("chat_") and bf.endswith(".jsonl"):
            m = re.match(r"^chat_(.+?)_(\d{8}-\d{6})\.jsonl$", bf)
            if m:
                c_slug = m.group(1).replace("_", " ").lower()
                date_str = m.group(2)
                try:
                    ts = datetime.datetime.strptime(date_str, "%Y%m%d-%H%M%S").timestamp()
                except:
                    ts = 0
                for k in all_chars:
                    norm_k = k.replace("-", " ").replace("_", " ")
                    if norm_k in c_slug or c_slug in norm_k:
                        if ts > all_chars[k]["last_active"]:
                            all_chars[k]["last_active"] = ts
                            all_chars[k]["latest_file"] = os.path.join(backups_dir, bf)

if os.path.exists(chats_dir):
    for cd in os.listdir(chats_dir):
        k = cd.lower()
        if k in all_chars:
            cpath = os.path.join(chats_dir, cd)
            for jf in glob.glob(os.path.join(cpath, "*.jsonl")):
                mtime = os.path.getmtime(jf)
                if mtime > all_chars[k]["last_active"]:
                    all_chars[k]["last_active"] = mtime
                    all_chars[k]["latest_file"] = jf

for k, v in all_chars.items():
    if v["messages"] == 0 and v["latest_file"] and os.path.exists(v["latest_file"]):
        try:
            with open(v["latest_file"], "rb") as fp:
                lines = sum(1 for _ in fp) - 1
                if lines > 0:
                    v["messages"] = lines
        except:
            pass

sorted_chars = sorted(all_chars.values(), key=lambda x: x["last_active"], reverse=True)
for c in sorted_chars:
    print("%s\t%d\t%d" % (c["name"].replace("\t", " ").replace("\n", " "), c["messages"], int(c["last_active"])))
