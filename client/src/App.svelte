<script lang="ts">
	import ConnectionStatus from './lib/components/common/ConnectionStatus.svelte';
</script>

<main>
	<h1>BrutalTank</h1>
	<p class="subtitle">M0 — client scaffolding</p>
	<ConnectionStatus />
</main>

<style>
	main {
		max-width: 40rem;
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
