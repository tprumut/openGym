package ch.duartesantos.opengym.watch

class ApiException(val code: String, message: String) : Exception(message)
