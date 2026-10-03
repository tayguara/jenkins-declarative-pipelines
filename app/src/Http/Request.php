<?php

declare(strict_types=1);

namespace App\Http;

final readonly class Request
{
    /**
     * @param array<string, mixed>  $query   decoded query string
     * @param array<string, string> $headers header values keyed by lower-case name
     */
    public function __construct(
        public string $method,
        public string $path,
        public array $query = [],
        public array $headers = [],
    ) {}

    /**
     * @param array<array-key, mixed> $server $_SERVER
     * @param array<string, mixed>    $query  $_GET
     */
    public static function fromGlobals(array $server, array $query): self
    {
        $method = $server['REQUEST_METHOD'] ?? 'GET';
        $uri = $server['REQUEST_URI'] ?? '/';
        $path = is_string($uri) ? parse_url($uri, PHP_URL_PATH) : null;

        $headers = [];
        foreach ($server as $key => $value) {
            if (str_starts_with((string) $key, 'HTTP_') && is_string($value)) {
                $headers[strtolower(str_replace('_', '-', substr((string) $key, 5)))] = $value;
            }
        }

        return new self(
            is_string($method) ? $method : 'GET',
            is_string($path) && $path !== '' ? $path : '/',
            $query,
            $headers,
        );
    }

    public function header(string $name): ?string
    {
        return $this->headers[strtolower($name)] ?? null;
    }

    public function queryParam(string $name): ?string
    {
        $value = $this->query[$name] ?? null;

        return is_string($value) ? $value : null;
    }
}
