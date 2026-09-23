# Porchlight Press

**Your hometown newspaper, rebuilt for your phone. Free, private, and honest about where every story comes from.**

Porchlight Press is a free Android app that puts together a real-looking daily newspaper for the place you live. It has local, regional, state, national, and world news, plus your weather. Every story tells you where it came from and links to the original reporting.

> **Status: in development.** Porchlight Press is being built now and will launch on **Google Play**. This page describes the app we're building. Watch or star this repository to follow along.

---

## What you get

📰 **A newspaper, not a feed.** A front page with a masthead, headlines, sections, and photos, laid out like the paper that used to land on your porch. Pick a classic newsprint look or a clean modern one, in light or dark.

📍 **News for where you actually live.** Tell the app your town and it builds your paper around it: **Local**, your **region**, your **state**, the **nation**, and the **world**. You can use your phone's approximate location, type your **ZIP or postal code**, or pick your town from a list. You can also follow more than one place, like home, where your parents live, or where you're headed next week.

🌦️ **Weather built in.** Today's conditions, the forecast, and severe-weather alerts for your area, straight from the U.S. National Weather Service (and MET Norway elsewhere in the world).

🔗 **Every story shows its sources.** Stories are short, neutral briefs that list every news outlet they're based on, with a link to read the original article. We send readers *to* local journalists; we don't replace them.

🤖 **AI you can see, not AI that hides.** Briefs are written by a small AI "newsroom" and **clearly labelled as AI-written** every time. Strict checks throw out any brief that adds a name, number, quote, or fact the original sources didn't report. When a brief doesn't pass, you see the original headline and a link instead. [How the AI works →](#how-the-ai-newsroom-works)

🌍 **Read it in your language.** Choose from dozens of languages when you set up the app, or change it any time in Settings. Menus, stories, weather, and even your downloaded PDF are translated **right on your phone**.

🔊 **Have the news read to you.** Tap **Listen** on any story, or play a whole section, in a natural-sounding voice. It keeps playing with your screen off and works with headphones and car Bluetooth. You choose the voice and speed.

📄 **Download today's paper as a PDF.** Save a newspaper-style PDF of your edition to read offline, print, or share. Open it in the app's built-in reader or any PDF app you like.

📤 **Share stories easily.** Send any story to friends and family through the usual Android share menu. They get the headline, a short summary, and a link. They don't need the app to read it.

✈️ **Works offline.** Your latest paper and saved stories stay readable without a connection.

🔔 **Notifications you control.** Severe-weather warnings, breaking local news, and morning and evening editions are each on/off switches, with quiet hours. We never send more than a few a day.

🔎 **Save and search.** Bookmark stories to keep them, and search everything you've downloaded.

---

## Free, with a few ads

Porchlight Press is **free to download and free to use**. There are no subscriptions, no paywalls, and no account to create. A small number of clearly marked ads in the app help keep it that way.

- Ads are labelled **"Advertisement"** and sit *between* sections, never inside a story.
- There are no pop-up or full-screen ads.
- You choose whether ads can be personalized. Without your consent, you only see non-personalized ads.

---

## Your privacy

We built this app to know as little about you as possible.

- **No account, no sign-in, no profile.**
- **Your location stays on your phone.** We turn it into a town name on the device. The weather service only gets a rough area (about 11 km / 7 miles across), never your exact spot.
- **Translation and read-aloud happen on your phone.** Your reading isn't sent anywhere to be translated or spoken.
- **Crash reports and usage statistics are off unless you turn them on.**
- **Your reading habits stay with you.** What you read, save, and search is stored only on your device.

Read the full [Privacy Policy](PRIVACY.md).

---

## How the AI newsroom works

1. A few times a day, Porchlight Press collects headlines and short summaries from **public news feeds and official government sources** in your area, like local newspapers, TV stations, city and county offices, and the weather service.
2. Reports about the same event are grouped together, so you see **one story with several sources** instead of the same news five times.
3. A small, open AI model writes a short, **neutral** brief using only what those sources reported.
4. Every brief goes through automatic checks before you see it. Any name, number, date, place, or quote that isn't in the original sources gets the brief rejected. Political stories get extra rules: no endorsements, no voting advice, and disagreements are attributed to whoever said them.
5. If a brief fails the checks, **you get the original headline and a link** instead. You never get a guess.

Every AI-written brief carries an **AI Newsroom** label and the full list of sources. AI can still make mistakes, so for anything important, tap through and read the original reporting. If something looks wrong, use **Report a problem with this story** in the app and we'll look into it.

---

## Where it works

Porchlight Press is built to work **anywhere in the world**. We're starting with the **Capital Region of New York** (Schenectady, Albany, Troy, Saratoga, and nearby towns) and adding more places over time. If your town doesn't have a local section yet, the app says so and shows your region, state, and country instead.

**Want your town added, or know a good local news source?** [Suggest it here](https://github.com/chartmann1590/porchlight-press/issues/new/choose).

---

## Frequently asked questions

**Is it really free?**
Yes. No subscription, no in-app purchases, no premium tier. The app shows a few ads to pay for itself.

**Do you write the news?**
No. The reporting comes from real news organizations and official sources, and every story links to them. Our AI only condenses what they reported into a short, neutral brief, and says so clearly.

**Can I trust the AI?**
Trust the sources, and use the brief as a quick summary. That's why every brief shows its sources and links. We reject any brief that adds facts the sources didn't report. If the checks fail, you just see the original headline.

**How often is the news updated?**
New editions come out several times a day: morning, midday, and evening. **Severe-weather alerts are checked much more often**, straight from the weather service, and don't wait for the next edition.

**Can I use it without sharing my location?**
Yes. Type your ZIP or postal code, or pick your town from a list. Location permission is optional.

**Is Porchlight Press a substitute for official emergency warnings?**
No. Always follow instructions from local officials and the National Weather Service or your country's weather authority.

**Is there an iPhone version?**
Not right now. Porchlight Press is Android-only for now.

---

## Help and contact

- **Questions or feedback:** [me@charleshartman.com](mailto:me@charleshartman.com)
- **Report a bug or suggest a feature or news source:** [open an issue](https://github.com/chartmann1590/porchlight-press/issues/new/choose)
- **Report a security problem:** see our [Security Policy](SECURITY.md) (please don't post security problems publicly)

---

## The fine print

- [Privacy Policy](PRIVACY.md)
- [Terms of Service](TERMS.md)
- [Content & Attribution Policy](CONTENT_POLICY.md)
- [Security Policy](SECURITY.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)

News articles, headlines, and photos belong to their original publishers and photographers. Porchlight Press links to their work and never republishes full articles. Weather data comes from the U.S. National Weather Service and MET Norway. Place names come from the U.S. Census Bureau and GeoNames. Licensed photos come from Wikimedia Commons, credited on each image.

---

## For developers

Porchlight Press is **open source** under the [Apache License 2.0](LICENSE). The "Porchlight Press" name and logo aren't covered by that license. Please use a different name for your own version. Want to help? Start with [CONTRIBUTING.md](CONTRIBUTING.md).
