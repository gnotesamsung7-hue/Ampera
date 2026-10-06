"""Generates Voltnutt-QR-Cards.pdf (A4, 9 cards per page, 63 x 88 mm). Re-run after editing SECTIONS."""
import io
import qrcode
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.utils import ImageReader
from reportlab.lib.colors import Color
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

F = "/usr/share/fonts/truetype/dejavu/"
pdfmetrics.registerFont(TTFont("Title", F + "DejaVuSansCondensed-Bold.ttf"))
pdfmetrics.registerFont(TTFont("Body", F + "DejaVuSansCondensed.ttf"))
pdfmetrics.registerFont(TTFont("Mono", F + "DejaVuSansMono.ttf"))

RED = (0.72, 0.20, 0.16)        # Voltnutt's faded red
AMBER = (0.80, 0.52, 0.05)
STEEL = (0.36, 0.40, 0.44)
INK = (0.11, 0.13, 0.15)

# (section title, colour, tic line, cards)
SECTIONS = [
    ("Moods", RED, "[Servo click]", [
        ("Happy", "EMOTION:HAPPY"), ("Excited", "EMOTION:EXCITED"), ("Curious", "EMOTION:CURIOUS"),
        ("Surprised", "EMOTION:SURPRISED"), ("Confused", "EMOTION:CONFUSED"), ("Love", "EMOTION:LOVE"),
        ("Hypnotized", "EMOTION:HYPNOTIZED"), ("Content", "EMOTION:CONTENT"), ("Sad", "EMOTION:SAD"),
        ("Bored", "EMOTION:BORED"), ("Sleepy", "EMOTION:SLEEPY"), ("Yawn", "EMOTION:YAWN"),
        ("Sneeze", "EMOTION:SNEEZE"), ("Hungry", "EMOTION:HUNGRY"), ("Searching", "EMOTION:SEARCHING"),
        ("Grumpy", "EMOTION:GRUMPY"), ("Dizzy", "EMOTION:DIZZY"), ("Neutral", "EMOTION:IDLE"),
    ]),
    ("Machine states", STEEL, "[Low-power hum]", [
        ("Power up", "EMOTION:POWER_UP"), ("Low battery", "EMOTION:LOW_BATTERY"), ("Overheating", "EMOTION:HOT"),
        ("Alert", "EMOTION:ALERT"), ("Party face", "EMOTION:PARTY"), ("Eating", "EMOTION:EATING"),
        ("Say: Status?", "SAY:All servos reporting for duty."), ("Say: Squeak", "SAY:My left knee squeaks. I call it character."),
        ("Say: Oil", "SAY:I smell oil. Is that me?"),
    ]),
    ("Games", AMBER, "[Servo click]", [
        ("Feed me!", "FEED_ME"), ("Food: Pizza", "FOOD:PIZZA"), ("Food: Adobo", "FOOD:ADOBO"),
        ("Food: Mango", "FOOD:MANGO"), ("Food: Cookie", "FOOD:COOKIE"), ("Food: Ice cream", "FOOD:ICE_CREAM"),
        ("Sleep", "SLEEP"), ("Wake up", "WAKE"), ("Party mode", "PARTY"),
    ]),
    ("More food", AMBER, "[Servo click]", [
        ("Food: Apple", "FOOD:APPLE"), ("Food: Banana", "FOOD:BANANA"), ("Food: Burger", "FOOD:BURGER"),
        ("Food: Fries", "FOOD:FRIES"), ("Food: Donut", "FOOD:DONUT"), ("Food: Rice", "FOOD:RICE"),
        ("Food: Noodles", "FOOD:NOODLES"), ("Food: Sushi", "FOOD:SUSHI"), ("Food: Battery", "FOOD:BATTERY"),
    ]),
    ("The fellowship", RED, "[Static feedback]", [
        ("Hello", "SAY:Hello, Voltnutt!"), ("Sparky", "SAY:Sparky says hi!"), ("Unit 7", "SAY:Unit 7 reporting for duty!"),
        ("Whirr", "SAY:Whirr is spinning in circles again!"), ("Origin Press", "SAY:Onward to the Origin Press!"),
        ("Good morning", "SAY:Good morning, little robot."), ("Good night", "SAY:Good night, little robot."),
        ("Thorne", "SAY:Rest now, Thorne. We'll get you there."), ("Brave", "SAY:You are braver than any empire, Voltnutt."),
    ]),
]

W, H = A4
CW, CH = 63 * mm, 88 * mm
COLS, ROWS = 3, 3
GX = (W - COLS * CW) / (COLS + 1)
GY = (H - 22 * mm - ROWS * CH) / (ROWS + 1)


def qr_image(payload):
    q = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=12, border=2)
    q.add_data(payload)
    q.make(fit=True)
    buf = io.BytesIO()
    q.make_image(fill_color="black", back_color="white").save(buf, format="PNG")
    buf.seek(0)
    return ImageReader(buf)


def red_eye(c, cx, cy, w, h):
    """Voltnutt's single eye: red rounded rect with a white-hot core."""
    r = min(w, h) * 0.28
    c.saveState()
    p = c.beginPath()
    p.roundRect(cx - w / 2, cy - h / 2, w, h, r)
    c.clipPath(p, stroke=0, fill=0)
    c.radialGradient(cx, cy, min(w, h) * 0.62,
                     [Color(1, 0.98, 0.92), Color(1, 0.83, 0.35), Color(1, 0.35, 0.08), Color(0.91, 0.08, 0.11)],
                     [0, 0.16, 0.45, 1], extend=True)
    c.restoreState()


def card_frame(c, x, y):
    c.setStrokeColorRGB(0.72, 0.74, 0.76)
    c.setDash(2, 2)
    c.setLineWidth(0.5)
    c.roundRect(x, y, CW, CH, 4 * mm, stroke=1, fill=0)
    c.setDash()


def rivets(c, x, y, w, h, inset=2.2 * mm, r=0.7 * mm):
    c.setFillColorRGB(0.80, 0.82, 0.84)
    for px, py in ((x + inset, y + inset), (x + w - inset, y + inset), (x + inset, y + h - inset), (x + w - inset, y + h - inset)):
        c.circle(px, py, r, stroke=0, fill=1)


def page_header(c, title, col):
    red_eye(c, GX + 4 * mm, H - 12.5 * mm, 6 * mm, 6.6 * mm)
    c.setFillColorRGB(*INK)
    c.setFont("Title", 15)
    c.drawString(GX + 10 * mm, H - 14.5 * mm, f"VOLTNUTT  ·  {title.upper()}")
    c.setFont("Body", 8.5)
    c.setFillColorRGB(0.35, 0.37, 0.4)
    c.drawRightString(W - GX, H - 14.5 * mm, "Cut along the dotted lines. Show a card to the front camera.")
    c.setFillColorRGB(*col)
    c.rect(GX, H - 18 * mm, W - 2 * GX, 1.2 * mm, stroke=0, fill=1)


def slot(i):
    r, k = divmod(i, COLS)
    return GX + k * (CW + GX), H - 22 * mm - (r + 1) * (CH + GY)


def qr_card(c, x, y, title, payload, col, tic):
    card_frame(c, x, y)
    # nameplate band
    bx, by, bw, bh = x + 3 * mm, y + CH - 14 * mm, CW - 6 * mm, 11 * mm
    c.setFillColorRGB(*col)
    c.roundRect(bx, by, bw, bh, 2 * mm, stroke=0, fill=1)
    rivets(c, bx, by, bw, bh, inset=1.8 * mm, r=0.55 * mm)
    red_eye(c, bx + 7 * mm, by + bh / 2, 6 * mm, 7 * mm)
    c.setFillColorRGB(1, 1, 1)
    c.setFont("Title", 9)
    c.drawString(bx + 12 * mm, by + bh / 2 + 0.6 * mm, "VOLTNUTT")
    c.setFont("Mono", 5.6)
    c.drawString(bx + 12 * mm, by + bh / 2 - 2.6 * mm, "UNIT VN-1 · PROJECT AEGIS")
    # QR
    q = 47 * mm
    c.drawImage(qr_image(payload), x + (CW - q) / 2, y + 22 * mm, q, q)
    # labels
    c.setFillColorRGB(*INK)
    c.setFont("Title", 13)
    c.drawCentredString(x + CW / 2, y + 15.5 * mm, title)
    c.setFont("Mono", 6.5)
    c.setFillColorRGB(*col)
    c.drawCentredString(x + CW / 2, y + 11 * mm, tic)
    c.setFillColorRGB(0.42, 0.44, 0.47)
    c.setFont("Mono", 6.2)
    shown = payload if len(payload) <= 34 else payload[:32] + ".."
    c.drawCentredString(x + CW / 2, y + 6 * mm, shown)


def cover(c):
    red_eye(c, W / 2, H - 70 * mm, 46 * mm, 51 * mm)
    c.setFillColorRGB(*INK)
    c.setFont("Title", 40)
    c.drawCentredString(W / 2, H - 118 * mm, "VOLTNUTT")
    c.setFont("Mono", 10)
    c.setFillColorRGB(*STEEL)
    c.drawCentredString(W / 2, H - 126 * mm, "QR CARD SET  ·  UNIT VN-1  ·  PROJECT AEGIS")
    lines = [
        ("How to use", None),
        ("1.  Print on A4 at 100% (actual size). Matte paper scans best.", None),
        ("2.  Cut along the dotted lines.", None),
        ("3.  Hold a card 20-40 cm in front of the phone's front camera.", None),
        ("4.  Feeding game: show 'Feed me!' first, then a food card within 45 seconds.", None),
        ("5.  'Sleep' puts him to sleep; a tap or the 'Wake up' card wakes him.", None),
        ("", None),
        ("Cards are ignored while driving. There are no eye-colour cards: Voltnutt's eye is always red.", "note"),
        ("The EyeBot cards work too, except their colour cards.", "note"),
    ]
    y = H - 150 * mm
    for text, kind in lines:
        if text == "How to use":
            c.setFont("Title", 14); c.setFillColorRGB(*RED)
        elif kind == "note":
            c.setFont("Body", 10); c.setFillColorRGB(0.38, 0.4, 0.43)
        else:
            c.setFont("Body", 11.5); c.setFillColorRGB(*INK)
        c.drawString(32 * mm, y, text)
        y -= 8 * mm
    c.setFont("Mono", 9)
    c.setFillColorRGB(*RED)
    c.drawCentredString(W / 2, 28 * mm, "[Static feedback]  One factory floor at a time.")
    c.showPage()


def main(out="Voltnutt-QR-Cards.pdf"):
    c = canvas.Canvas(out, pagesize=A4)
    c.setTitle("Voltnutt QR cards")
    c.setAuthor("Voltnutt")
    cover(c)
    for title, col, tic, cards in SECTIONS:
        for start in range(0, len(cards), COLS * ROWS):
            page_header(c, title, col)
            for i, (t, p) in enumerate(cards[start:start + COLS * ROWS]):
                qr_card(c, *slot(i), t, p, col, tic)
            c.showPage()
    c.save()


if __name__ == "__main__":
    main()
