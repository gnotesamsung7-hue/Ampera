"""Generates EyeBot-QR-Cards.pdf (A4, 9 cards per page, 63 x 88 mm). Re-run after editing CARDS."""
import io
import qrcode
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas

TEAL = (0.25, 0.88, 0.77)
SECTIONS = [
    ("Emotion cards", (0.25, 0.88, 0.77), [
        ("Happy", "HAPPY"), ("Excited", "EXCITED"), ("Curious", "CURIOUS"),
        ("Surprised", "SURPRISED"), ("Confused", "CONFUSED"), ("Lovestruck", "LOVESTRUCK"),
        ("Hypnotized", "HYPNOTIZED"), ("Content", "CONTENT"), ("Sad", "SAD"),
        ("Bored", "BORED"), ("Sleepy", "SLEEPY"), ("Yawn", "YAWN"),
        ("Sneeze", "SNEEZE"), ("Hungry", "HUNGRY"), ("Searching", "SEARCHING"),
        ("Eating", "EATING"), ("Party face", "EMOTION:PARTY"), ("Neutral", "IDLE"),
    ]),
    ("v4 extras", (0.25, 0.88, 0.77), [
        ("Grumpy", "GRUMPY"), ("Hot", "HOT"), ("Power up", "POWER_UP"),
        ("Low battery", "LOW_BATTERY"), ("Alert", "ALERT"), ("Say good morning", "SAY:Good morning!"),
        ("Say I love you", "SAY:I love you!"), ("Orange eyes", "COLOR:ORANGE"), ("Teal eyes", "COLOR:TEAL"),
    ]),
    ("Game cards", (1.0, 0.62, 0.18), [
        ("Feed Me!", "FEED_ME"), ("Food: Apple", "FOOD:APPLE"), ("Food: Pizza", "FOOD:PIZZA"),
        ("Food: Cookie", "FOOD:COOKIE"), ("Food: Mango", "FOOD:MANGO"), ("Sleep", "SLEEP"),
        ("Wake Up", "WAKE"), ("Party Mode", "PARTY"), ("Say hello", "SAY:Hello! I'm EyeBot."),
    ]),
    ("Eye colour cards (QR)", (0.64, 0.36, 1.0), [
        ("Red eyes", "COLOR:RED"), ("Blue eyes", "COLOR:BLUE"), ("Green eyes", "COLOR:GREEN"),
        ("Cyan eyes", "COLOR:CYAN"), ("Purple eyes", "COLOR:PURPLE"), ("Yellow eyes", "COLOR:YELLOW"),
        ("Pink eyes", "COLOR:PINK"), ("White eyes", "COLOR:WHITE"), ("Normal eyes", "COLOR:RESET"),
    ]),
]
# Solid cards for the live colour detector (hold in front of the camera, centre of view).
SOLIDS = [
    ("RED", (0.86, 0.12, 0.12)), ("GREEN", (0.08, 0.63, 0.24)), ("BLUE", (0.08, 0.27, 0.78)),
    ("CYAN", (0.0, 0.67, 0.82)), ("YELLOW", (0.98, 0.84, 0.0)), ("MAGENTA", (0.82, 0.08, 0.63)),
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


def eyes(c, cx, cy, col, size=5 * mm):
    c.setFillColorRGB(*col)
    for dx in (-0.8, 0.8):
        c.roundRect(cx + dx * size - size / 2, cy - size * 0.6, size, size * 1.2, size * 0.28, stroke=0, fill=1)


def card_frame(c, x, y):
    c.setStrokeColorRGB(0.75, 0.75, 0.75)
    c.setDash(2, 2)
    c.setLineWidth(0.5)
    c.roundRect(x, y, CW, CH, 4 * mm, stroke=1, fill=0)
    c.setDash()


def page_header(c, title, col):
    c.setFillColorRGB(0.1, 0.1, 0.1)
    c.setFont("Helvetica-Bold", 15)
    c.drawString(GX, H - 14 * mm, f"EyeBot v4  -  {title}")
    c.setFont("Helvetica", 8.5)
    c.setFillColorRGB(0.35, 0.35, 0.35)
    c.drawRightString(W - GX, H - 14 * mm, "Cut along the dotted lines. Show a card to the front camera.")
    c.setFillColorRGB(*col)
    c.rect(GX, H - 17 * mm, W - 2 * GX, 1.2 * mm, stroke=0, fill=1)


def slot(i):
    r, k = divmod(i, COLS)
    x = GX + k * (CW + GX)
    y = H - 22 * mm - (r + 1) * (CH + GY) + GY * 0.0
    return x, y


def qr_card(c, x, y, title, payload, col):
    card_frame(c, x, y)
    c.setFillColorRGB(*col)
    c.roundRect(x + 3 * mm, y + CH - 13 * mm, CW - 6 * mm, 10 * mm, 2.5 * mm, stroke=0, fill=1)
    eyes(c, x + CW / 2, y + CH - 8 * mm, (0.05, 0.05, 0.05), 3.2 * mm)
    q = 50 * mm
    c.drawImage(qr_image(payload), x + (CW - q) / 2, y + 17 * mm, q, q)
    c.setFillColorRGB(0.08, 0.08, 0.08)
    c.setFont("Helvetica-Bold", 13)
    c.drawCentredString(x + CW / 2, y + 10 * mm, title)
    c.setFont("Courier", 7)
    c.setFillColorRGB(0.4, 0.4, 0.4)
    c.drawCentredString(x + CW / 2, y + 5 * mm, payload if len(payload) <= 30 else payload[:28] + "..")


def solid_card(c, x, y, name, rgb):
    card_frame(c, x, y)
    c.setFillColorRGB(*rgb)
    c.roundRect(x + 2.5 * mm, y + 2.5 * mm, CW - 5 * mm, CH - 5 * mm, 3 * mm, stroke=0, fill=1)
    c.setFillColorRGB(1, 1, 1)
    c.setFont("Helvetica-Bold", 11)
    c.drawCentredString(x + CW / 2, y + 7 * mm, name)


def main(out="EyeBot-QR-Cards.pdf"):
    c = canvas.Canvas(out, pagesize=A4)
    c.setTitle("EyeBot v4 QR cards")
    for title, col, cards in SECTIONS:
        for start in range(0, len(cards), COLS * ROWS):
            page_header(c, title, col)
            for i, (t, p) in enumerate(cards[start:start + COLS * ROWS]):
                qr_card(c, *slot(i), t, p, col)
            c.showPage()
    page_header(c, "Colour cards (no QR - eyes copy the colour)", (0.86, 0.12, 0.12))
    for i, (n, rgb) in enumerate(SOLIDS):
        solid_card(c, *slot(i), n, rgb)
    c.setFillColorRGB(0.3, 0.3, 0.3)
    c.setFont("Helvetica", 9)
    c.drawString(GX, GY + 4 * mm, "Hold a colour card so it fills the middle of the camera view for ~0.3 s. Matte paper works best.")
    c.showPage()
    c.save()


if __name__ == "__main__":
    main()
