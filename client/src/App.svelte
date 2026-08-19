<script lang="ts">
	import ConnectionStatus from './lib/components/common/ConnectionStatus.svelte';
	import MatchScreen from './lib/components/MatchScreen.svelte';
	import { connectionStore } from './lib/stores/connectionStore';
</script>

<main>
	<h1>BrutalTank</h1>
	<p class="subtitle">M1 — single-shot smoke test</p>
	<ConnectionStatus />

	{#if $connectionStore.status === 'open'}
		<MatchScreen />
	{/if}
</main>

<style>
	main {
		max-width: 64rem;
		margin: 3rem auto;
		padding: 0 1rem;
		font-family: system-ui, sans-serif;
	}

	h1 {
		margin-bottom: 0;
	}

	.subtitle {
		color: #888;
		margin-top: 0.25rem;
	}
</style>
