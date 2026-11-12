#!/usr/bin/env python3
"""검증용 Access Token(JWT, RS256)을 만든다. 표준 라이브러리와 openssl 만 쓴다.

사용 예:
  scripts/mint-token.py --key "$WORK/keys/rs256-v1-private.pem" --sub 100 --roles BUYER
  scripts/mint-token.py --key "$WORK/keys/rs256-v1-private.pem" --sub 10 --roles SELLER BUYER
  scripts/mint-token.py --key "$WORK/keys/rs256-v1-private.pem" --sub 1 --roles ADMIN --expired

claim 모양은 common 의 JwtConfig / TestAccessTokens 와 같다.
  헤더: alg=RS256, typ=at+jwt, kid=rs256-v1
  본문: iss=paldo-gotgan, aud=[paldo-gotgan-api], sub=<회원 ID 숫자 문자열>, iat, exp, roles=[...]
"""
import argparse, base64, json, subprocess, sys, time


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--key", required=True, help="PKCS#8 PEM 개인키 경로")
    p.add_argument("--sub", required=True, help="회원 ID")
    p.add_argument("--roles", nargs="*", default=[], help="BUYER SELLER ADMIN")
    p.add_argument("--kid", default="rs256-v1")
    p.add_argument("--expired", action="store_true", help="1분 전에 만료된 토큰")
    a = p.parse_args()

    now = int(time.time())
    exp = now - 60 if a.expired else now + 1800
    header = {"alg": "RS256", "typ": "at+jwt", "kid": a.kid}
    claims = {"iss": "paldo-gotgan", "aud": ["paldo-gotgan-api"], "sub": a.sub,
              "iat": exp - 1800, "exp": exp, "roles": a.roles}
    signing_input = (b64url(json.dumps(header, separators=(",", ":")).encode()) + "." +
                     b64url(json.dumps(claims, separators=(",", ":")).encode()))
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", a.key],
                         input=signing_input.encode(), capture_output=True, check=True).stdout
    sys.stdout.write(signing_input + "." + b64url(sig) + "\n")


if __name__ == "__main__":
    main()
