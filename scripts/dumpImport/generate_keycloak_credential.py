#!/usr/bin/env python

import hashlib
import binascii

password = "admin"
salt = "1234ASDFasdf"
bsalt = binascii.a2b_base64(salt)
hash_iterations = 27500
hashed_password = hashlib.pbkdf2_hmac('sha256', password.encode('utf-8'), bsalt, hash_iterations)
hashed_password_base64 = binascii.b2a_base64(hashed_password).decode('utf-8').strip()
print(hashed_password_base64)

