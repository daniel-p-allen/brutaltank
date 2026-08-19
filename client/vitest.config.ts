import { defineConfig } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// Separate from vite.config.ts to avoid type friction between vite's and
// vitest's `defineConfig` — this project only unit-tests plain TS modules
// (lib/net, lib/stores), not Svelte components, so jsdom is enough.
export default defineConfig({
	plugins: [svelte()],
	test: {
		environment: 'jsdom',
		include: ['src/**/*.test.ts']
	}
});
