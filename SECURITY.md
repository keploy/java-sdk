# Security Policy

## Reporting a Vulnerability

We value security for the project very highly. We encourage all users to report any vulnerabilities they discover to us.
If you find a security vulnerability in the Keploy project, please report it responsibly by sending an email to hello@keploy.io

At this juncture, we don't have a bug bounty program. We are a small team trying to solve a big problem. We urge you to report any vulnerabilities responsibly
so that we can continue building a secure application for the entire community.

## Verifying releases

Every artifact on Maven Central (`io.keploy`) and every `.asc` file on a
GitHub Release is signed by the Keploy release key:

- Releases from v2.1.3 on: key id `F844278E3B5F9051`, fingerprint
  `0167 FEA3 204A 35D8 C1E1  1EE9 F844 278E 3B5F 9051`.
- Releases up to v2.1.1: key id `8541784E4EC36FB8`, fingerprint
  `D51E 37C1 E5D2 F092 72E3  8869 8541 784E 4EC3 6FB8`.

Fetch the key from keyserver.ubuntu.com and check a file:

```sh
gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys 0167FEA3204A35D8C1E11EE9F844278E3B5F9051
gpg --verify keploy-sdk-<version>.jar.asc keploy-sdk-<version>.jar
```
