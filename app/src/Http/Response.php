<?php

declare(strict_types=1);

namespace App\Http;

use JsonException;

final readonly class Response
{
    /**
     * @param array<string, string> $headers
     */
    public function __construct(
        public int $status,
        public string $body = '',
        public array $headers = [],
    ) {}

    public static function text(string $body, int $status = 200): self
    {
        return new self($status, $body, ['Content-Type' => 'text/plain; charset=utf-8']);
    }

    /**
     * @param array<string, string> $data
     *
     * @throws JsonException
     */
    public static function json(array $data, int $status = 200): self
    {
        return new self(
            $status,
            json_encode($data, JSON_THROW_ON_ERROR | JSON_INVALID_UTF8_SUBSTITUTE),
            ['Content-Type' => 'application/json'],
        );
    }
}
