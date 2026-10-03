<?php

declare(strict_types=1);

namespace App;

/**
 * The deploy token lives in a file (a mounted secret), never in an environment variable.
 */
final class TokenFile
{
    public static function read(?string $path): ?string
    {
        if ($path === null || $path === '') {
            return null;
        }

        $contents = @file_get_contents($path);
        if ($contents === false) {
            return null;
        }

        $token = trim($contents);

        return $token === '' ? null : $token;
    }
}
