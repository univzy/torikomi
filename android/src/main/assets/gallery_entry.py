import ipaddress
import runpy
import socket


def public_addresses(records):
    if not records or any(not ipaddress.ip_address(record[4][0].split("%", 1)[0]).is_global for record in records):
        raise OSError("Private network URLs are not supported")
    return records


if __name__ == "__main__":
    original_lookup = socket.getaddrinfo

    def lookup(*args, **kwargs):
        return public_addresses(original_lookup(*args, **kwargs))

    # Apply the same public-address boundary to extractor requests and redirects.
    socket.getaddrinfo = lookup
    runpy.run_module("gallery_dl", run_name="__main__")
