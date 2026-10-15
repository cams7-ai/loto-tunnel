#!/usr/bin/env python3
"""Compare contemporaneous direct-phone and EC2 SOCKS responses as IPv6 values."""
import ipaddress
import sys
if len(sys.argv) != 3:
    raise SystemExit('Uso: compare_ipv6.py IPV6_DO_CELULAR IPV6_DA_EC2')
try:
    phone, proxy = (ipaddress.IPv6Address(value.strip()) for value in sys.argv[1:])
except ValueError as error:
    raise SystemExit(f'Resposta inválida: {error}')
if not phone.is_global or not proxy.is_global:
    raise SystemExit('Os dois endereços devem ser IPv6 públicos globais.')
if phone != proxy:
    raise SystemExit(f'Validação NÃO aprovada: celular={phone.compressed}, proxy={proxy.compressed}. Investigue a rede e o endereço de origem.')
print(f'IPv6 iguais: {phone.compressed}. Confirme também listener, rede e registros no aplicativo.')
