import angular from 'angular';
import 'angular-ui-router-extras';

import './query';
import './results';
import '../preview/image';
import '../components/gr-top-bar/gr-top-bar';
import '../components/gr-panel/gr-panel';

import searchTemplate        from './view.html!text';
import searchResultsTemplate from './results.html!text';
import panelTemplate        from '../components/gr-panel/gr-panel.html!text';


export var search = angular.module('kahuna.search', [
    'ct.ui.router.extras.dsr',
    'kahuna.search.query',
    'kahuna.search.results',
    'kahuna.preview.image',
    'gr.topBar',
    'grPanel'
]);

// TODO: add a resolver here so that if we error (e.g. 401) we don't keep trying
// to render - similar to the image controller see:
// https://github.com/guardian/media-service/pull/478
search.config(['$stateProvider',
               function($stateProvider) {

    $stateProvider.state('search', {
        // FIXME [1]: This state should be abstract, but then we can't navigate to
        // it, which we need to do to access it's deeper / remembered chile state
        url: '/',
        template: searchTemplate,
        deepStateRedirect: {
            // Inject a transient $stateParams for the results state
            // below to pick up and expose
            fn: ['$dsr$', function($dsr$) {
                const params = angular.extend({}, $dsr$.redirect.params, {
                    isDeepStateRedirect: true
                });
                return {state: $dsr$.redirect.state, params};
            }]
        }
    });

    $stateProvider.state('search.results', {
        url: 'search?query&ids&since&nonFree&uploadedBy&until&orderBy',
        // Non-URL parameters
        params: {
            // Routing-level property indicating whether the state has
            // been loaded as part of a deep-state redirect. Note that
            // this param gets cleared below so it should never reach
            // controllers
            isDeepStateRedirect: {value: false, type: 'bool'}
        },
        data: {
            title: function(params) {
                return params.query ? params.query : 'search';
            }
        },
        resolve: {
            // Helper state to determine whether this is just
            // reloading a previous search state, or a new search
            isReloadingPreviousSearch: ['$stateParams', function($stateParams) {
                const isDeepStateRedirect = $stateParams.isDeepStateRedirect;
                // *Clear* that transient routing-level flag so we
                // *don't pollute the $stateParams
                delete $stateParams.isDeepStateRedirect;
                return isDeepStateRedirect;
            }]
        },
        views: {
            results: {
                template: searchResultsTemplate,
                controller: 'SearchResultsCtrl',
                controllerAs: 'ctrl'
            },
            panel: {
                template: panelTemplate,
                controller: 'GrPanel',
                controllerAs: 'ctrl'
            }
        }
    });
}]);

// FIXME: This is here if you go to another state directly e.g. `'/images/id'`
// and then navigate to search. As it has no remembered `deepStateRedirect`,
// we just land on `/`. See [1].
search.run(['$rootScope', '$state', function($rootScope, $state) {
    $rootScope.$on('$stateChangeSuccess', (_, toState) => {
        if (toState.name === 'search') {
            $state.go('search.results', null, {reload: true});
        }
    });
}]);
