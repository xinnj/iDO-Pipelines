# Version: 1

import sys
import base64
from io import BytesIO
import qrcode

"""
Generate QR code image from a URL, and convert the image to base64 string.
Usage: generate_qrcode.py <URL>
"""

if __name__ == '__main__':
    if len(sys.argv) != 2:
        print("USAGE: generate_qrcode.py <URL>")
        sys.exit(1)

    data = sys.argv[1]

    qr = qrcode.QRCode(box_size=5)
    qr.add_data(data)
    qr.make()
    img = qr.make_image()
    # img.save(path)

    buffered = BytesIO()
    img.save(buffered, format="PNG")
    img_str = base64.b64encode(buffered.getvalue()).decode("utf-8")
    print(img_str)
