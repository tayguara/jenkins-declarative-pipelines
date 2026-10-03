<?php

declare(strict_types=1);

namespace App;

use JsonException;

/**
 * Identity of the release being served: what was built and which blue/green slot it lives in.
 */
final readonly class ReleaseInfo
{
    public function __construct(
        public string $version,
        public string $build,
        public string $slot,
    ) {}

    /**
     * Reads the RELEASE file written by bin/package. A missing or unusable file means a local checkout.
     */
    public static function fromReleaseRoot(string $root, string $slot): self
    {
        $json = @file_get_contents($root . '/RELEASE');
        if ($json === false) {
            return new self('dev', '0', $slot);
        }

        try {
            $data = json_decode($json, true, flags: JSON_THROW_ON_ERROR);
        } catch (JsonException) {
            return new self('dev', '0', $slot);
        }

        $version = is_array($data) ? ($data['version'] ?? null) : null;
        $build = is_array($data) ? ($data['build'] ?? null) : null;
        if (!is_string($version) || $version === '' || !is_string($build)) {
            return new self('dev', '0', $slot);
        }

        return new self($version, $build, $slot);
    }
}
